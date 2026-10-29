# Pantalla TV

Convierte la TV de la sala en una pantalla extra de la computadora, por wifi,
para trabajar con Claude Code en grande.

## Cómo funciona
1. `pantalla_tv.py` le pide a GNOME una pantalla virtual (1920×1080) a la derecha de la tuya.
2. La graba en video H.264 y la envía a la TV (puerto 8081).
3. La app **Familia TV** la recibe y la muestra sola a pantalla completa.

## Arrancar
```
./arrancar.sh                 # pantalla extra (extendida) en la TV 192.168.20.17
./arrancar.sh --espejo        # la TV muestra lo mismo que tu pantalla, como un cable HDMI
./arrancar.sh --ancho 1280 --alto 720 --kbps 4000   # más liviano
```
Arrastra la ventana de la terminal (o cualquier ventana) hacia la derecha
de tu pantalla y aparecerá en la TV. `Ctrl+C` la apaga. En la TV, el botón
**Atrás** del control también la cierra.

## Requisitos
GNOME en Wayland, GStreamer con `pipewiresrc` y `openh264enc`, python3-gi.

## Estructura
- `pantalla_tv.py` — crea la pantalla virtual y envía el video.
- `arrancar.sh` — atajo para arrancarlo.
- `ayudante.py` — deja que la TV encienda y apague la proyección.

## Encender y apagar desde la TV
`ayudante.py` espera órdenes de la TV (puerto 8090) y se anuncia a la app cada 30 s.
En la TV, las tarjetas **Proyectar mi PC** y **Pantalla extra** la encienden; **Atrás** la apaga.
Solo obedece a la IP de la TV y con la clave guardada en `~/.config/pantalla-tv/clave` (fuera de git).

## Arranque automático
`pantalla-tv.service` es una copia del servicio instalado en `~/.config/systemd/user/` (se activa con `systemctl --user enable --now pantalla-tv.service`).
