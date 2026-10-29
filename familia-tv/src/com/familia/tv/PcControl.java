package com.familia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends on/off orders to the helper running on the family computer. */
final class PcControl {
    interface Callback {
        void onResult(boolean exito);
    }

    private static final int TIMEOUT_MS = 4000;
    private static final long MAX_AGE_MS = 90_000L;

    private PcControl() { }

    static boolean disponible(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE);
        String ip = prefs.getString(HomePrefs.PC_IP, "");
        String key = prefs.getString(HomePrefs.PC_KEY, "");
        int port = prefs.getInt(HomePrefs.PC_PORT, 0);
        long seen = prefs.getLong(HomePrefs.PC_SEEN, 0L);
        if (ip == null || ip.isEmpty() || key == null || !key.matches("^[0-9a-f]{32}$")) return false;
        if (port < 1024 || port > 65535) return false;
        long age = System.currentTimeMillis() - seen;
        return seen > 0L && age >= 0L && age < MAX_AGE_MS;
    }

    static void encender(Context context, String modo) {
        encender(context, modo, null);
    }

    static void encender(Context context, String modo, Callback callback) {
        String mode = "extra".equals(modo) ? "extra" : "espejo";
        send(context, "/encender", "modo=" + mode + "&clave=", callback);
    }

    static void apagar(Context context) {
        apagar(context, null);
    }

    static void apagar(Context context, Callback callback) {
        send(context, "/apagar", "clave=", callback);
    }

    private static void send(Context context, String path, String prefix, Callback callback) {
        final Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE);
        final String ip = prefs.getString(HomePrefs.PC_IP, "");
        final String key = prefs.getString(HomePrefs.PC_KEY, "");
        final int port = prefs.getInt(HomePrefs.PC_PORT, 0);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Callback result = callback;

        if (ip == null || ip.isEmpty() || key == null || !key.matches("^[0-9a-f]{32}$")
                || port < 1024 || port > 65535) {
            if (result != null) handler.post(new Runnable() {
                @Override public void run() { result.onResult(false); }
            });
            return;
        }

        final String target = "http://" + ip + ":" + port + path;
        final String body = prefix + key;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(target).openConnection();
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                        output.flush();
                    }
                    ok = connection.getResponseCode() == 200;
                } catch (Exception ignored) {
                    ok = false;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                if (result != null) {
                    final boolean exito = ok;
                    handler.post(new Runnable() {
                        @Override public void run() { result.onResult(exito); }
                    });
                }
            }
        }, "pc-control");
        thread.setDaemon(true);
        thread.start();
    }
}
