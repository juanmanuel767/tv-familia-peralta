# TV Familia Peralta

Software propio para la TV Hyundai Google TV de la sala y la computadora.

## Estructura
- `familia-tv/` — app de la TV (Java puro, sin Gradle): pantalla de inicio "Familia Peralta"
  con reloj, clima de Neiva, mensajes y fotos desde el celular (http://192.168.20.17:8080),
  apps favoritas, animaciones y receptor de la pantalla de trabajo. Ver su README.
- `pantalla-tv/` — programa de la computadora (GNOME Wayland) que envía la pantalla a la TV
  en modo espejo o como pantalla extra, y el ayudante que deja a la TV encenderla y apagarla.

## Arrancar
```
cd familia-tv && ./build.sh            # compilar la app
./conectar-tv.sh                       # conectar adb con la TV sin código
adb install -r build/familia-tv.apk    # instalar (luego: adb shell pm grant com.familia.tv android.permission.WRITE_SECURE_SETTINGS)
cd ../pantalla-tv && ./arrancar.sh --espejo   # proyectar a mano (o desde la tarjeta de la TV)
```

## Claves
La clave del ayudante vive en `~/.config/pantalla-tv/clave` y la llave de firma en
`familia-tv/build/` (ambas fuera de git).

## Cuidado
No desactivar "Google TV Home" (com.google.android.apps.tv.launcherx): deja la TV reiniciándose.
