#!/usr/bin/env python3
"""Crea una pantalla virtual en GNOME y la envía en video H.264 a la app Familia TV.

Protocolo (TCP, la computadora se conecta a la TV, puerto 8081):
  cabecera: b"FTV1" + ancho (u16 BE) + alto (u16 BE)
  cuadros:  longitud (u32 BE) + unidad de acceso H.264 en formato Annex-B
"""
import argparse
import signal
import socket
import struct
import sys
import threading
import time

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstVideo", "1.0")
from gi.repository import Gio, GLib, Gst, GstVideo  # noqa: E402

MUTTER = "org.gnome.Mutter.ScreenCast"


class Pantalla:
    def __init__(self, tv, puerto, ancho, alto, fps, kbps, espejo=None):
        self.tv, self.puerto, self.espejo = tv, puerto, espejo
        self.ancho, self.alto, self.fps, self.kbps = ancho, alto, fps, kbps
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.loop = GLib.MainLoop()
        self.sesion = None
        self.pipeline = None
        self.conexion = None
        self.lock = threading.Lock()
        self.envio = threading.Lock()
        self.ultimo_envio = 0.0
        self.activo = True

    def _llamar(self, ruta, interfaz, metodo, args, tipo):
        return self.bus.call_sync(MUTTER, ruta, interfaz, metodo, args,
                                  GLib.VariantType(tipo), 0, -1, None).unpack()

    def iniciar(self):
        self.sesion, = self._llamar("/org/gnome/Mutter/ScreenCast", MUTTER, "CreateSession",
                                    GLib.Variant("(a{sv})", ({},)), "(o)")
        opciones = {"cursor-mode": GLib.Variant("u", 1)}
        if self.espejo:
            flujo, = self._llamar(self.sesion, MUTTER + ".Session", "RecordMonitor",
                                  GLib.Variant("(sa{sv})", (self.espejo, opciones)), "(o)")
        else:
            flujo, = self._llamar(self.sesion, MUTTER + ".Session", "RecordVirtual",
                                  GLib.Variant("(a{sv})", (opciones,)), "(o)")
        self.bus.signal_subscribe(MUTTER, MUTTER + ".Stream", "PipeWireStreamAdded",
                                  flujo, None, 0, self._flujo_listo)
        self._llamar(self.sesion, MUTTER + ".Session", "Start", None, "()")
        threading.Thread(target=self._conectar_tv, daemon=True).start()
        GLib.timeout_add_seconds(2, self._latido)
        self.loop.run()

    def _flujo_listo(self, _conn, _sender, _path, _iface, _signal, params):
        nodo = params.unpack()[0]
        self.pipeline = Gst.parse_launch(
            # Sin videorate: guardaba un cuadro de reserva y retrasaba el ratón en la TV.
            f"pipewiresrc path={nodo} do-timestamp=true always-copy=true ! video/x-raw,max-framerate={self.fps}/1 ! "
            f"videoconvert ! videoscale ! video/x-raw,format=I420,width={self.ancho},height={self.alto} ! "
            f"capssetter caps=video/x-raw,framerate={self.fps}/1 ! "
            f"openh264enc usage-type=screen rate-control=bitrate bitrate={self.kbps * 1000} "
            f"gop-size={self.fps * 2} complexity=low ! "
            "h264parse config-interval=-1 ! video/x-h264,stream-format=byte-stream,alignment=au ! "
            "appsink name=salida emit-signals=true sync=false max-buffers=2 drop=true")
        salida = self.pipeline.get_by_name("salida")
        salida.connect("new-sample", self._nuevo_cuadro)
        self.pipeline.get_bus().add_signal_watch()
        self.pipeline.get_bus().connect("message::error", self._error)
        self.pipeline.set_state(Gst.State.PLAYING)
        if self.espejo:
            print(f"Espejo de {self.espejo} listo ({self.ancho}x{self.alto}).", flush=True)
        else:
            print(f"Pantalla virtual lista ({self.ancho}x{self.alto}). Arrastra ventanas hacia la derecha.", flush=True)

    def _error(self, _bus, mensaje):
        err, _ = mensaje.parse_error()
        print("Error de video:", err.message, file=sys.stderr, flush=True)
        self.detener()

    def _conectar_tv(self):
        while self.activo:
            if self.conexion is None:
                try:
                    s = socket.create_connection((self.tv, self.puerto), timeout=5)
                    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    s.settimeout(10)
                    s.sendall(b"FTV1" + struct.pack(">HH", self.ancho, self.alto))
                    with self.lock:
                        self.conexion = s
                    print(f"Conectado a la TV {self.tv}:{self.puerto}", flush=True)
                    self._pedir_cuadro_completo()
                except OSError:
                    pass
            time.sleep(3)

    def _pedir_cuadro_completo(self):
        if self.pipeline is None:
            return
        evento = GstVideo.video_event_new_upstream_force_key_unit(Gst.CLOCK_TIME_NONE, True, 0)
        self.pipeline.get_by_name("salida").send_event(evento)

    def _nuevo_cuadro(self, salida):
        muestra = salida.emit("pull-sample")
        with self.lock:
            s = self.conexion
        if s is None:
            return Gst.FlowReturn.OK
        buf = muestra.get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.OK
        try:
            self._enviar(s, struct.pack(">I", info.size) + bytes(info.data))
        finally:
            buf.unmap(info)
        return Gst.FlowReturn.OK

    def _enviar(self, s, datos):
        try:
            with self.envio:
                s.sendall(datos)
                self.ultimo_envio = time.monotonic()
        except OSError:
            print("Se perdió la conexión con la TV; reintentando…", flush=True)
            with self.lock:
                if self.conexion is s:
                    self.conexion = None
            try:
                s.close()
            except OSError:
                pass

    def _latido(self):
        """Avisa a la TV que seguimos aquí cuando la pantalla no cambia."""
        with self.lock:
            s = self.conexion
        if s is not None and time.monotonic() - self.ultimo_envio > 2:
            threading.Thread(target=self._enviar, args=(s, struct.pack(">I", 0)), daemon=True).start()
        return self.activo

    def detener(self, *_):
        if not self.activo:
            return
        self.activo = False
        if self.pipeline is not None:
            self.pipeline.set_state(Gst.State.NULL)
        if self.sesion is not None:
            try:
                self._llamar(self.sesion, MUTTER + ".Session", "Stop", None, "()")
            except GLib.Error:
                pass
        with self.lock:
            if self.conexion is not None:
                self.conexion.close()
                self.conexion = None
        self.loop.quit()
        print("Pantalla de la TV apagada.", flush=True)


def monitor_principal():
    """Devuelve (conector, ancho, alto) del monitor principal de GNOME."""
    bus = Gio.bus_get_sync(Gio.BusType.SESSION)
    _serial, monitores, logicos, _props = bus.call_sync(
        "org.gnome.Mutter.DisplayConfig", "/org/gnome/Mutter/DisplayConfig",
        "org.gnome.Mutter.DisplayConfig", "GetCurrentState", None, None, 0, -1, None).unpack()
    principal = next((l[5][0][0] for l in logicos if l[4]), monitores[0][0][0])
    for (conector, *_), modos, _ in monitores:
        if conector != principal:
            continue
        for _id, ancho, alto, _hz, _escala, _escalas, props in modos:
            if props.get("is-current"):
                return conector, ancho - ancho % 2, alto - alto % 2
    return principal, 1920, 1080


def main():
    p = argparse.ArgumentParser(description="Usar la TV como pantalla extra por wifi")
    p.add_argument("--tv", default="192.168.20.17", help="IP de la TV")
    p.add_argument("--puerto", type=int, default=8081)
    p.add_argument("--ancho", type=int, default=None, help="por defecto 1920 (o el tamaño de tu pantalla en espejo)")
    p.add_argument("--alto", type=int, default=None)
    p.add_argument("--espejo", action="store_true", help="mostrar en la TV lo mismo que tu pantalla, como un cable HDMI")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--kbps", type=int, default=8000, help="calidad del video en kbit/s")
    a = p.parse_args()
    Gst.init(None)
    monitor = None
    ancho, alto = 1920, 1080
    if a.espejo:
        monitor, ancho, alto = monitor_principal()
    pantalla = Pantalla(a.tv, a.puerto, a.ancho or ancho, a.alto or alto, a.fps, a.kbps, monitor)
    for sig in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, lambda *_: (pantalla.detener(), False)[1])
    pantalla.iniciar()


if __name__ == "__main__":
    main()
