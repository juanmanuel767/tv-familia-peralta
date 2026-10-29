# Centro del hogar (Familia TV)

Aplicación de inicio para Android TV, escrita en Java puro y sin Gradle ni
librerías externas. Muestra el reloj, el clima, las tarjetas de aplicaciones
favoritas, el mensaje que envía la familia y las fotos compartidas.

## Qué hace

- Reloj y fecha en la esquina superior derecha.
- Clima de la ciudad elegida (por defecto Bogotá), actualizado cada 30 minutos.
- Mensaje grande y translúcido enviado desde el celular.
- Fondo con las fotos familiares, cambiando cada 20 segundos con un fundido.
- Dirección web para abrir el centro desde el celular.
- Servidor web local en el puerto 8080 para enviar mensajes, fotos y cambiar
  la ciudad sin instalar ninguna aplicación en el teléfono.
- Se guardan como máximo 20 fotos; al llegar una nueva se borra la más antigua.
- Las fotos se reducen a 1280 píxeles de lado mayor y solo se mantienen dos en
  memoria, pensando en televisiones de 1 GB de RAM.

## Estructura

```
AndroidManifest.xml              configuración de la app
assets/fondo-familiar.jpg        fondo cuando todavía no hay fotos
src/com/familia/tv/MainActivity.java   pantalla, reloj, tarjetas y mensaje
src/com/familia/tv/HomePrefs.java      nombres de los datos guardados
src/com/familia/tv/WeatherService.java clima de Open-Meteo
src/com/familia/tv/WebServer.java      servidor web para el celular
src/com/familia/tv/PhotoRotator.java   fondo de fotos con poco consumo de RAM
src/com/familia/tv/ScreenReceiver.java recibe la pantalla del computador (puerto 8081)
src/com/familia/tv/ScreenActivity.java muestra esa pantalla a pantalla completa
build.sh                         compila y firma el APK
build/                           resultados de la compilación (ignorada por git)
```

## Cómo compilar

Necesitas el SDK de Android en `$HOME/Android/Sdk`, con `build-tools/35.0.0` y
`platforms/android-35`. Después, desde esta carpeta:

```bash
./build.sh
```

El resultado es `build/familia-tv.apk`. La primera vez se crea solo el archivo
de firma `build/debug.keystore` (alias `familia`, contraseña `android`). No hace
falta Gradle ni conexión a internet.

## Cómo instalar en la televisión

Con la TV encendida y el depurador por red activo:

```bash
adb -s 192.168.20.17:5555 install -r build/familia-tv.apk
```

Cambia la dirección por la que corresponda a tu televisión.

## Cómo usarlo desde el celular

1. Conecta el celular a la misma red wifi que la televisión.
2. Mira abajo a la derecha de la pantalla de la TV: aparece
   `Desde tu celular: http://IP:8080`.
3. Abre esa dirección en el navegador del celular.
4. Desde la página puedes:
   - escribir un mensaje (máximo 300 caracteres) y pulsar **Enviar a la TV**;
   - borrar el mensaje con **Borrar mensaje**;
   - subir una foto con el selector de imágenes;
   - cambiar el nombre, la latitud y la longitud de la ciudad del clima.

La página está pensada para el móvil y funciona sin instalar nada. El servidor
solo está disponible mientras la aplicación está abierta y se apaga al cerrar
la pantalla de inicio.

## Botón Inicio del control

El servicio de accesibilidad «Inicio Familia Peralta» hace que el botón Inicio
del control abra la aplicación (la pulsación larga sigue funcionando como en el
sistema) y se activa con:

```bash
adb shell settings put secure enabled_accessibility_services com.familia.tv/com.familia.tv.InicioService && adb shell settings put secure accessibility_enabled 1
```


## Pantalla de trabajo

La televisión también puede mostrar a pantalla completa la pantalla virtual de
la computadora. Para eso, en la computadora se ejecuta
`../pantalla-tv/arrancar.sh`; se conecta a la TV por wifi en el puerto 8081 y la
imagen aparece sola. Con el botón ATRÁS del control se cierra.
