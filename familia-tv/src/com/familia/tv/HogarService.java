package com.familia.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the home servers (web on 8080 and screen on 8081) running in the background. */
public final class HogarService extends Service {
    private static final String CHANNEL = "hogar";

    private WebServer server;
    private ScreenReceiver screenReceiver;

    @Override public void onCreate() {
        super.onCreate();
        BootReceiver.enableHomeButtonService(this);
        startInForeground();
        server = new WebServer(this, null);
        server.start();
        screenReceiver = new ScreenReceiver(this);
        screenReceiver.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        if (screenReceiver != null) screenReceiver.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Centro del hogar",
                    NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Centro del hogar")
                .setContentText("Centro del hogar activo")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }
}
