package com.familia.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Tiny dependency-free HTTP server that lets a phone send messages and photos to the TV. */
final class WebServer {
    interface Listener {
        void onMessageChanged();
        void onCityChanged();
        void onPhotosChanged();
    }

    private static final int PORT = 8080;
    private static final int MAX_PHOTO_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_PHOTOS = 20;

    private final Context context;
    private final Listener listener;
    private final ExecutorService clients;
    private volatile boolean stopped;
    private ServerSocket serverSocket;

    WebServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.clients = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "web-corto");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    void start() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "servidor-web");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            serverSocket = socket;
            while (!stopped) {
                final Socket client = socket.accept();
                clients.execute(new Runnable() {
                    @Override public void run() { handle(client); }
                });
            }
        } catch (IOException ignored) {
            // Stopped or port unavailable; the home screen keeps working without the server.
        }
    }

    void stop() {
        stopped = true;
        ServerSocket socket = serverSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        serverSocket = null;
        clients.shutdownNow();
    }

    private void handle(Socket socket) {
        InputStream input = null;
        OutputStream output = null;
        try {
            socket.setSoTimeout(10000);
            input = new BufferedInputStream(socket.getInputStream());
            output = socket.getOutputStream();

            String requestLine = readLine(input, 8192);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respondText(output, 400, "Solicitud inválida");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int question = path.indexOf('?');
            if (question >= 0) path = path.substring(0, question);

            Map<String, String> headers = readHeaders(input);
            if (headers == null) {
                respondText(output, 431, "Cabeceras demasiado grandes");
                return;
            }

            long length = 0;
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null && !lengthHeader.isEmpty()) {
                try {
                    length = Long.parseLong(lengthHeader.trim());
                } catch (NumberFormatException invalid) {
                    respondText(output, 400, "Longitud inválida");
                    return;
                }
            }
            if (length < 0) length = 0;
            long limit = "/foto".equals(path) ? MAX_PHOTO_BYTES
                    : "/pc".equals(path) ? 1024 : MAX_FORM_BYTES;
            if (length > limit) {
                respondText(output, 413, "El contenido es demasiado grande");
                return;
            }

            byte[] body = readBody(input, (int) length);
            if (body == null) {
                respondText(output, 400, "No se pudo leer el contenido");
                return;
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                respondHtml(output, page());
            } else if ("GET".equals(method) && "/favicon.ico".equals(path)) {
                respond(output, 204, "No Content", "text/plain; charset=utf-8", new byte[0]);
            } else if ("POST".equals(method) && "/mensaje".equals(path)) {
                handleMessage(output, headers, body);
            } else if ("POST".equals(method) && "/borrar".equals(path)) {
                handleDelete(output, headers);
            } else if ("POST".equals(method) && "/ciudad".equals(path)) {
                handleCity(output, headers, body);
            } else if ("POST".equals(method) && "/foto".equals(path)) {
                handlePhoto(output, headers, body);
            } else if ("POST".equals(method) && "/pc".equals(path)) {
                handlePc(output, socket, body);
            } else if ("/".equals(path) || "/index.html".equals(path) || "/mensaje".equals(path)
                    || "/borrar".equals(path) || "/ciudad".equals(path) || "/foto".equals(path)
                    || "/pc".equals(path)) {
                respondText(output, 405, "Método no permitido");
            } else {
                respondText(output, 404, "No encontrado");
            }
        } catch (Exception ignored) {
            try {
                if (output != null) respondText(output, 500, "Error interno");
            } catch (Exception ignoredToo) { }
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void handleMessage(OutputStream output, Map<String, String> headers, byte[] body) throws IOException {
        Map<String, String> form = parseForm(body);
        String message = form.get("texto");
        if (message == null) message = "";
        message = message.trim();
        if (message.length() > 300) message = message.substring(0, 300);

        SharedPreferences prefs = prefs();
        prefs.edit()
                .putString(HomePrefs.MESSAGE, message)
                .putString(HomePrefs.MESSAGE_TIME, new SimpleDateFormat("HH:mm", new Locale("es", "CO")).format(new Date()))
                .apply();
        if (listener != null) listener.onMessageChanged();
        respondDone(output, headers, "Mensaje enviado a la TV");
    }

    private void handleDelete(OutputStream output, Map<String, String> headers) throws IOException {
        prefs().edit().remove(HomePrefs.MESSAGE).remove(HomePrefs.MESSAGE_TIME).apply();
        if (listener != null) listener.onMessageChanged();
        respondDone(output, headers, "Mensaje borrado");
    }

    private void handleCity(OutputStream output, Map<String, String> headers, byte[] body) throws IOException {
        Map<String, String> form = parseForm(body);
        String name = form.get("nombre");
        String latText = form.get("lat");
        String lonText = form.get("lon");
        if (name == null || latText == null || lonText == null) {
            respondError(output, headers, "Faltan datos de la ciudad");
            return;
        }
        name = name.trim();
        if (name.isEmpty() || name.length() > 60) {
            respondError(output, headers, "El nombre de la ciudad no es válido");
            return;
        }
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(latText.trim());
            lon = Double.parseDouble(lonText.trim());
        } catch (NumberFormatException invalid) {
            respondError(output, headers, "La latitud y la longitud deben ser números");
            return;
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            respondError(output, headers, "La latitud va de -90 a 90 y la longitud de -180 a 180");
            return;
        }
        prefs().edit()
                .putString(HomePrefs.CITY, name)
                .putString(HomePrefs.LAT, String.valueOf(lat))
                .putString(HomePrefs.LON, String.valueOf(lon))
                .apply();
        if (listener != null) listener.onCityChanged();
        respondDone(output, headers, "Ciudad actualizada");
    }

    private void handlePhoto(OutputStream output, Map<String, String> headers, byte[] body) throws IOException {
        String type = headers.get("content-type");
        if (type == null) type = "";
        type = type.toLowerCase(Locale.US);
        if (!type.startsWith("image/")) {
            respondError(output, headers, "El archivo debe ser una imagen");
            return;
        }
        if (body.length == 0) {
            respondError(output, headers, "La imagen está vacía");
            return;
        }

        String extension = "jpg";
        if (type.contains("png")) extension = "png";
        else if (type.contains("webp")) extension = "webp";
        else if (type.contains("gif")) extension = "gif";

        File directory = PhotoRotator.photoDir(context);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File target = new File(directory, "foto-" + stamp + "." + extension);
        int suffix = 1;
        while (target.exists()) {
            target = new File(directory, "foto-" + stamp + "-" + suffix + "." + extension);
            suffix++;
        }

        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write(body);
            stream.flush();
        } catch (IOException error) {
            respondError(output, headers, "No se pudo guardar la foto");
            return;
        }
        prunePhotos(directory);
        SharedPreferences prefs = prefs();
        prefs.edit().putInt(HomePrefs.FOTOS_VERSION, prefs.getInt(HomePrefs.FOTOS_VERSION, 0) + 1).apply();
        if (listener != null) listener.onPhotosChanged();
        respondDone(output, headers, "Foto recibida");
    }

    private void handlePc(OutputStream output, Socket socket, byte[] body) throws IOException {
        Map<String, String> form = parseForm(body);
        String portText = form.get("puerto");
        String key = form.get("clave");
        if (portText == null || key == null) {
            respondText(output, 400, "Faltan datos de la computadora");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException invalid) {
            respondText(output, 400, "El puerto no es válido");
            return;
        }
        if (port < 1024 || port > 65535) {
            respondText(output, 400, "El puerto debe ir de 1024 a 65535");
            return;
        }
        if (!key.matches("^[0-9a-f]{32}$")) {
            respondText(output, 400, "La clave no es válida");
            return;
        }
        String ip = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
        if (ip == null || ip.isEmpty()) {
            respondText(output, 400, "No se pudo saber la dirección de la computadora");
            return;
        }
        prefs().edit()
                .putString(HomePrefs.PC_IP, ip)
                .putInt(HomePrefs.PC_PORT, port)
                .putString(HomePrefs.PC_KEY, key)
                .putLong(HomePrefs.PC_SEEN, System.currentTimeMillis())
                .apply();
        respondText(output, 200, "ok");
    }

    private void prunePhotos(File directory) {
        File[] found = directory.listFiles();
        if (found == null) return;
        List<File> photos = new ArrayList<>();
        for (File file : found) if (file.isFile()) photos.add(file);
        photos.sort((left, right) -> left.getName().compareTo(right.getName()));
        for (int i = 0; i < photos.size() - MAX_PHOTOS; i++) {
            try { photos.get(i).delete(); } catch (Exception ignored) { }
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE);
    }

    private void respondDone(OutputStream output, Map<String, String> headers, String message) throws IOException {
        if (wantsJson(headers)) {
            respondJson(output, 200, "{\"ok\":true,\"mensaje\":\"" + escapeJson(message) + "\"}");
        } else {
            redirect(output);
        }
    }

    private void respondError(OutputStream output, Map<String, String> headers, String message) throws IOException {
        if (wantsJson(headers)) {
            respondJson(output, 400, "{\"ok\":false,\"error\":\"" + escapeJson(message) + "\"}");
        } else {
            respondText(output, 400, message);
        }
    }

    private boolean wantsJson(Map<String, String> headers) {
        String requested = headers.get("x-requested-with");
        if (requested != null && requested.toLowerCase(Locale.US).contains("fetch")) return true;
        String accept = headers.get("accept");
        return accept != null && accept.toLowerCase(Locale.US).contains("application/json");
    }

    private void redirect(OutputStream output) throws IOException {
        String head = "HTTP/1.1 303 See Other\r\nLocation: /\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void respondJson(OutputStream output, int code, String json) throws IOException {
        respond(output, code, code == 200 ? "OK" : "Bad Request", "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private void respondHtml(OutputStream output, String html) throws IOException {
        respond(output, 200, "OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void respondText(OutputStream output, int code, String text) throws IOException {
        respond(output, code, reason(code), "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(OutputStream output, int code, String reason, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) output.write(body);
        output.flush();
    }

    private static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 204) return "No Content";
        if (code == 400) return "Bad Request";
        if (code == 404) return "Not Found";
        if (code == 405) return "Method Not Allowed";
        if (code == 413) return "Payload Too Large";
        if (code == 431) return "Request Header Fields Too Large";
        return "Error";
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES);
            if (line == null) return null;
            if (line.isEmpty()) return headers;
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) return null;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
    }

    private static String readLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') break;
            if (current == '\r') continue;
            if (line.size() >= maximum) throw new IOException("Línea demasiado larga");
            line.write(current);
        }
        if (current == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        if (length == 0) return new byte[0];
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read == -1) return null;
            offset += read;
        }
        return body;
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> values = new HashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), "UTF-8");
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                values.put(key, value);
            } catch (Exception ignored) { }
        }
        return values;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String page() {
        SharedPreferences prefs = prefs();
        String city = escapeHtml(prefs.getString(HomePrefs.CITY, HomePrefs.DEFAULT_CITY));
        String lat = escapeHtml(prefs.getString(HomePrefs.LAT, String.valueOf(HomePrefs.DEFAULT_LAT)));
        String lon = escapeHtml(prefs.getString(HomePrefs.LON, String.valueOf(HomePrefs.DEFAULT_LON)));
        String message = prefs.getString(HomePrefs.MESSAGE, "");
        String time = prefs.getString(HomePrefs.MESSAGE_TIME, "");
        String messageBox;
        if (message == null || message.trim().isEmpty()) {
            messageBox = "<p class=\"empty\">Todavía no hay ningún mensaje en la televisión.</p>";
        } else {
            messageBox = "<blockquote>" + escapeHtml(message) + "</blockquote>"
                    + "<p class=\"time\">Recibido a las " + escapeHtml(time) + "</p>";
        }

        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Centro del hogar</title><style>"
                + ":root{color-scheme:dark}"
                + "*{box-sizing:border-box}"
                + "body{margin:0;min-height:100vh;padding:22px 16px 40px;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
                + "background:radial-gradient(circle at 20% 0%,#243049 0,#131722 45%,#0a0c12 100%);color:#eef1f8}"
                + "main{max-width:560px;margin:0 auto}"
                + "h1{margin:0 0 6px;font-size:1.65rem;letter-spacing:.2px}"
                + ".sub{margin:0 0 20px;color:#aab3c8;font-size:.95rem}"
                + ".card{background:rgba(255,255,255,.065);border:1px solid rgba(255,255,255,.1);border-radius:18px;"
                + "padding:18px;margin:0 0 16px;box-shadow:0 10px 30px rgba(0,0,0,.25)}"
                + "h2{margin:0 0 12px;font-size:1.02rem;color:#ffc2df;font-weight:650}"
                + "label{display:block;margin:0 0 6px;font-size:.85rem;color:#b9c3d8}"
                + "textarea,input[type=text],input[type=number]{width:100%;padding:12px 13px;border-radius:12px;"
                + "border:1px solid rgba(255,255,255,.16);background:#101522;color:#fff;font:inherit;outline:none}"
                + "textarea{min-height:96px;resize:vertical}"
                + "textarea:focus,input:focus{border-color:#ff79b6;box-shadow:0 0 0 3px rgba(255,121,182,.15)}"
                + "button{margin-top:12px;width:100%;padding:13px 16px;border:0;border-radius:12px;cursor:pointer;"
                + "font:inherit;font-weight:650;color:#16121a;background:linear-gradient(135deg,#ff9ac9,#ff79b6)}"
                + "button.secondary{background:linear-gradient(135deg,#7fc0ff,#52b1ff);color:#08111e}"
                + "button.danger{background:transparent;color:#ff9aa9;border:1px solid rgba(255,120,140,.5)}"
                + "input[type=file]{width:100%;padding:11px;border-radius:12px;border:1px dashed rgba(255,255,255,.25);"
                + "background:rgba(0,0,0,.18);color:#cfd7e8}"
                + ".row{display:flex;gap:10px}.row>div{flex:1}"
                + "blockquote{margin:0;padding:13px 15px;border-left:4px solid #ff79b6;background:rgba(0,0,0,.22);"
                + "border-radius:0 10px 10px 0;font-size:1.06rem;line-height:1.5;white-space:pre-wrap;word-break:break-word}"
                + ".time{margin:8px 0 0;color:#9aa6bd;font-size:.82rem}"
                + ".empty{margin:0;color:#9aa6bd;font-style:italic}"
                + "#estado{min-height:1.3em;margin:10px 0 0;font-size:.9rem;color:#9ff0c2}"
                + "#estado.error{color:#ff9aa9}"
                + "footer{margin-top:22px;text-align:center;color:#78839a;font-size:.8rem}"
                + "@media(max-width:420px){.row{display:block}.row>div{flex:none}}"
                + "</style></head><body><main>"
                + "<h1>Centro del hogar</h1>"
                + "<p class=\"sub\">Escribe, envía fotos y cambia el clima de la televisión.</p>"
                + "<section class=\"card\"><h2>Mensaje en la televisión</h2>" + messageBox
                + "<form id=\"formMensaje\"><label for=\"texto\">Nuevo mensaje (máximo 300 caracteres)</label>"
                + "<textarea id=\"texto\" name=\"texto\" maxlength=\"300\" placeholder=\"Hola familia…\" required></textarea>"
                + "<button type=\"submit\">Enviar a la TV</button></form>"
                + "<form id=\"formBorrar\"><button class=\"danger\" type=\"submit\">Borrar mensaje</button></form></section>"
                + "<section class=\"card\"><h2>Enviar una foto</h2>"
                + "<p class=\"empty\">Se guardan las 20 fotos más recientes y se muestran como fondo.</p>"
                + "<form id=\"formFoto\"><label for=\"foto\">Foto desde tu celular</label>"
                + "<input id=\"foto\" type=\"file\" accept=\"image/*\"><button class=\"secondary\" type=\"submit\">Subir foto</button></form></section>"
                + "<section class=\"card\"><h2>Ciudad del clima</h2><form id=\"formCiudad\">"
                + "<label for=\"nombre\">Ciudad</label><input id=\"nombre\" name=\"nombre\" type=\"text\" maxlength=\"60\" value=\"" + city + "\" required>"
                + "<div class=\"row\"><div><label for=\"lat\">Latitud</label><input id=\"lat\" name=\"lat\" type=\"number\" step=\"any\" min=\"-90\" max=\"90\" value=\"" + lat + "\" required></div>"
                + "<div><label for=\"lon\">Longitud</label><input id=\"lon\" name=\"lon\" type=\"number\" step=\"any\" min=\"-180\" max=\"180\" value=\"" + lon + "\" required></div></div>"
                + "<button type=\"submit\">Guardar ciudad</button></form></section>"
                + "<p id=\"estado\" role=\"status\"></p>"
                + "<footer>Familia TV · Centro del hogar</footer>"
                + "</main><script>"
                + "const estado=document.getElementById('estado');"
                + "function aviso(t,malo){estado.textContent=t;estado.className=malo?'error':'';}"
                + "async function enviar(url,opciones){const opt=Object.assign({},opciones||{});"
                + "opt.headers=Object.assign({'X-Requested-With':'fetch'},opt.headers||{});"
                + "const r=await fetch(url,opt);"
                + "let d={};try{d=await r.json()}catch(e){}if(!r.ok)throw new Error(d.error||'No se pudo completar');return d;}"
                + "document.getElementById('formMensaje').addEventListener('submit',async e=>{e.preventDefault();try{"
                + "await enviar('/mensaje',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:new URLSearchParams({texto:document.getElementById('texto').value})});"
                + "document.getElementById('texto').value='';aviso('Mensaje enviado a la TV');}catch(x){aviso(x.message,true);}});"
                + "document.getElementById('formBorrar').addEventListener('submit',async e=>{e.preventDefault();try{"
                + "await enviar('/borrar',{method:'POST'});aviso('Mensaje borrado');}catch(x){aviso(x.message,true);}});"
                + "document.getElementById('formCiudad').addEventListener('submit',async e=>{e.preventDefault();try{"
                + "await enviar('/ciudad',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:new URLSearchParams({nombre:document.getElementById('nombre').value,"
                + "lat:document.getElementById('lat').value,lon:document.getElementById('lon').value})});"
                + "aviso('Ciudad guardada. El clima se actualizará pronto.');}catch(x){aviso(x.message,true);}});"
                + "document.getElementById('formFoto').addEventListener('submit',async e=>{e.preventDefault();"
                + "const f=document.getElementById('foto').files[0];if(!f){aviso('Elige una foto primero',true);return;}"
                + "try{await enviar('/foto',{method:'POST',headers:{'Content-Type':f.type||'image/jpeg'},body:f});"
                + "document.getElementById('foto').value='';aviso('Foto enviada a la TV');}catch(x){aviso(x.message,true);}});"
                + "</script></body></html>";
    }
}
