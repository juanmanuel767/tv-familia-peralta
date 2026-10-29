#!/usr/bin/env python3
"""Ayudante que deja a la TV encender y apagar la proyección de esta computadora.

- Cada 30 s se anuncia a la app Familia TV (POST http://TV:8080/pc) con su puerto y una clave.
- Escucha en el puerto 8090 órdenes que solo acepta desde la IP de la TV y con esa clave:
    POST /encender   modo=espejo|extra
    POST /apagar
    GET  /estado
"""
import argparse
import hmac
import os
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CARPETA = os.path.dirname(os.path.abspath(__file__))
ARCHIVO_CLAVE = os.path.expanduser("~/.config/pantalla-tv/clave")


def leer_clave():
    if not os.path.exists(ARCHIVO_CLAVE):
        os.makedirs(os.path.dirname(ARCHIVO_CLAVE), mode=0o700, exist_ok=True)
        with open(os.open(ARCHIVO_CLAVE, os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
            f.write(secrets.token_hex(16))
    with open(ARCHIVO_CLAVE) as f:
        return f.read().strip()


class Proyeccion:
    def __init__(self, tv):
        self.tv = tv
        self.proceso = None
        self.modo = None
        self.lock = threading.Lock()

    def encender(self, modo):
        with self.lock:
            self._apagar()
            orden = [sys.executable, "-I", os.path.join(CARPETA, "pantalla_tv.py"), "--tv", self.tv]
            if modo == "espejo":
                orden.append("--espejo")
            self.proceso = subprocess.Popen(orden, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.modo = modo
            print(time.strftime("%T"), "encendida en modo", modo, flush=True)

    def apagar(self):
        with self.lock:
            self._apagar()

    def _apagar(self):
        if self.proceso is not None and self.proceso.poll() is None:
            self.proceso.send_signal(2)
            try:
                self.proceso.wait(5)
            except subprocess.TimeoutExpired:
                self.proceso.kill()
            print(time.strftime("%T"), "apagada", flush=True)
        self.proceso = None
        self.modo = None

    def estado(self):
        with self.lock:
            if self.proceso is not None and self.proceso.poll() is None:
                return self.modo
            return "apagada"


def crear_manejador(proyeccion, clave, ip_tv):
    class Manejador(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def _responder(self, codigo, texto):
            cuerpo = texto.encode()
            self.send_response(codigo)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(cuerpo)))
            self.end_headers()
            self.wfile.write(cuerpo)

        def _autorizado(self, campos):
            if self.client_address[0] != ip_tv:
                return False
            return hmac.compare_digest(campos.get("clave", ""), clave)

        def _campos(self):
            largo = int(self.headers.get("Content-Length") or 0)
            if largo > 1024:
                return None
            datos = self.rfile.read(largo).decode("utf-8", "replace")
            return {k: v[0] for k, v in urllib.parse.parse_qs(datos).items()}

        def do_GET(self):
            if self.path == "/estado" and self.client_address[0] == ip_tv:
                return self._responder(200, proyeccion.estado())
            self._responder(404, "no")

        def do_POST(self):
            campos = self._campos()
            if campos is None:
                return self._responder(413, "demasiado grande")
            if not self._autorizado(campos):
                return self._responder(403, "no autorizado")
            if self.path == "/encender":
                modo = campos.get("modo", "espejo")
                if modo not in ("espejo", "extra"):
                    return self._responder(400, "modo inválido")
                proyeccion.encender(modo)
                return self._responder(200, "encendida")
            if self.path == "/apagar":
                proyeccion.apagar()
                return self._responder(200, "apagada")
            self._responder(404, "no")

    return Manejador


def anunciarse(tv, puerto, clave):
    datos = urllib.parse.urlencode({"puerto": puerto, "clave": clave}).encode()
    while True:
        try:
            urllib.request.urlopen(f"http://{tv}:8080/pc", data=datos, timeout=5).close()
        except OSError:
            pass
        time.sleep(30)


def main():
    p = argparse.ArgumentParser(description="Deja que la TV encienda y apague la proyección")
    p.add_argument("--tv", default="192.168.20.17")
    p.add_argument("--puerto", type=int, default=8090)
    a = p.parse_args()
    clave = leer_clave()
    proyeccion = Proyeccion(a.tv)
    servidor = ThreadingHTTPServer(("0.0.0.0", a.puerto), crear_manejador(proyeccion, clave, a.tv))
    threading.Thread(target=anunciarse, args=(a.tv, a.puerto, clave), daemon=True).start()
    print(f"Ayudante listo en el puerto {a.puerto}; esperando órdenes de la TV {a.tv}", flush=True)
    try:
        servidor.serve_forever()
    finally:
        proyeccion.apagar()


if __name__ == "__main__":
    main()
