package com.familia.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Abre Familia Peralta cuando se pulsa el botón Inicio del control. */
public final class InicioService extends AccessibilityService {
    private static final String TAG = "FamiliaInicio";

    @Override public boolean onKeyEvent(KeyEvent e) {
        if (e == null || e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.isLongPress() || e.getRepeatCount() > 0) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Exception ex) {
                Log.e(TAG, "No se pudo abrir Familia Peralta", ex);
            }
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override public void onInterrupt() {
    }
}
