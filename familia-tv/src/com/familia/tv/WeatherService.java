package com.familia.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fetches a small weather summary every 30 minutes on a background thread. */
final class WeatherService {
    interface Listener {
        void onWeatherUpdated();
    }

    private static final long REFRESH_MILLIS = 30L * 60L * 1000L;
    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean stopped;
    private boolean wakeRequested;
    private Thread thread;

    WeatherService(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (thread != null) return;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "clima");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        Thread current = thread;
        if (current != null) current.interrupt();
        thread = null;
    }

    /** Asks the background thread to fetch again soon (for example after changing the city). */
    void requestRefresh() {
        synchronized (lock) {
            wakeRequested = true;
            lock.notifyAll();
        }
    }

    private void loop() {
        while (!stopped) {
            fetchOnce();
            synchronized (lock) {
                if (wakeRequested) {
                    wakeRequested = false;
                    continue;
                }
                try {
                    lock.wait(REFRESH_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                wakeRequested = false;
            }
        }
    }

    private void fetchOnce() {
        SharedPreferences prefs = context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE);
        String city = prefs.getString(HomePrefs.CITY, HomePrefs.DEFAULT_CITY);
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(prefs.getString(HomePrefs.LAT, String.valueOf(HomePrefs.DEFAULT_LAT)));
            lon = Double.parseDouble(prefs.getString(HomePrefs.LON, String.valueOf(HomePrefs.DEFAULT_LON)));
        } catch (NumberFormatException invalid) {
            lat = HomePrefs.DEFAULT_LAT;
            lon = HomePrefs.DEFAULT_LON;
        }

        String summary = "";
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude="
                    + URLEncoder.encode(String.valueOf(lat), "UTF-8")
                    + "&longitude=" + URLEncoder.encode(String.valueOf(lon), "UTF-8")
                    + "&current=temperature_2m,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto";
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "FamiliaTV/1.0");
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                input = connection.getInputStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);

                JSONObject root = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                JSONObject current = root.getJSONObject("current");
                int temperature = (int) Math.round(current.getDouble("temperature_2m"));
                int code = current.getInt("weather_code");
                JSONObject daily = root.getJSONObject("daily");
                int maximum = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
                int minimum = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));

                summary = String.format(Locale.US, "%s  %d°  %s  (máx %d° / mín %d°)",
                        city, temperature, description(code), maximum, minimum);
            }
        } catch (Exception ignored) {
            summary = "";
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }

        prefs.edit().putString(HomePrefs.WEATHER, summary).apply();
        if (!stopped) listener.onWeatherUpdated();
    }

    private static String description(int code) {
        if (code == 0) return "Despejado";
        if (code == 1 || code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code >= 61 && code <= 67) return "Lluvia";
        if (code >= 71 && code <= 77) return "Nieve";
        if (code >= 80 && code <= 82) return "Chubascos";
        if (code == 85 || code == 86) return "Nieve";
        if (code >= 95) return "Tormenta";
        return "Nublado";
    }
}
