package com.familia.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** Starts the home service after the television boots. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        enableWirelessDebugging(context);
        enableHomeButtonService(context);
        Intent service = new Intent(context, HogarService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    /** Keeps wireless debugging on after a reboot so the paired computer reconnects without a new code. */
    private static void enableWirelessDebugging(Context context) {
        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
        } catch (SecurityException ignored) {
            // WRITE_SECURE_SETTINGS has not been granted with adb; the user can turn it on in Settings.
        }
    }

    /** Turns our Home-button service back on if an update or reboot switched it off. */
    static void enableHomeButtonService(Context context) {
        final String ours = context.getPackageName() + "/" + InicioService.class.getName();
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty() || "null".equals(enabled)) {
                enabled = ours;
            } else if (!(":" + enabled + ":").contains(":" + ours + ":")) {
                enabled = enabled + ":" + ours;
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
            // Without WRITE_SECURE_SETTINGS the user enables it in Settings > Accessibility.
        }
    }
}
