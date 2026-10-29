package com.familia.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Fullscreen view of the computer's virtual screen, decoded with MediaCodec. */
public final class ScreenActivity extends Activity {
    private static final String TAG = "FamiliaPantalla";
    private static final int MAX_UNIT_BYTES = 4 * 1024 * 1024;

    private final Object codecLock = new Object();
    private final Object connectionLock = new Object();

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView hint;
    private ScreenReceiver.Connection connection;
    private MediaCodec codec;
    private Thread decodeThread;
    private volatile boolean running;
    private volatile boolean userAskedToClose;
    private volatile boolean closedByItself;
    private int videoWidth;
    private int videoHeight;
    private long framesShown;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                beginStream();
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                layoutSurface();
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                stopStream();
            }
        });
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        hint = new TextView(this);
        hint.setText("Pantalla de trabajo · Claude Code");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(dp(6), 0f, dp(2), 0xAA000000);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(32);
        root.addView(hint, hintParams);

        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                layoutSurface();
            }
        });

        hint.animate().setStartDelay(2000).setDuration(600).alpha(0f).withEndAction(new Runnable() {
            @Override public void run() {
                if (hint != null && hint.getParent() != null) root.removeView(hint);
            }
        });
    }

    private void beginStream() {
        if (decodeThread != null) return;
        ScreenReceiver.Connection active = ScreenReceiver.takeConnection();
        if (active == null) {
            closedByItself = true;
            finish();
            return;
        }
        synchronized (connectionLock) {
            connection = active;
        }
        videoWidth = active.width;
        videoHeight = active.height;
        layoutSurface();
        running = true;
        decodeThread = new Thread(new Runnable() {
            @Override public void run() {
                decodeLoop();
            }
        }, "screen-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private void layoutSurface() {
        if (surfaceView == null || root == null) return;
        if (videoWidth <= 0 || videoHeight <= 0) return;
        int containerWidth = root.getWidth();
        int containerHeight = root.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) return;
        float scale = Math.min((float) containerWidth / videoWidth, (float) containerHeight / videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale));
        int height = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(params);
    }

    private void decodeLoop() {
        MediaCodec decoder = null;
        ScreenReceiver.Connection active;
        synchronized (connectionLock) {
            active = connection;
        }
        try {
            if (active == null) throw new IOException("no connection");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
            if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            decoder = MediaCodec.createDecoderByType("video/avc");
            synchronized (codecLock) {
                codec = decoder;
            }
            decoder.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            decoder.start();
            Log.i(TAG, "Decodificador " + decoder.getName() + " configurado: "
                    + videoWidth + "x" + videoHeight);
            // Show each picture as soon as it is decoded instead of waiting for the next one to arrive.
            final MediaCodec output = decoder;
            Thread outputThread = new Thread(new Runnable() {
                @Override public void run() {
                    MediaCodec.BufferInfo outInfo = new MediaCodec.BufferInfo();
                    try {
                        while (running) drainOutput(output, outInfo, 10000);
                    } catch (IllegalStateException ignored) {
                        // The decoder was released while closing.
                    }
                }
            }, "screen-output");
            outputThread.setDaemon(true);
            outputThread.start();

            DataInputStream in = new DataInputStream(new BufferedInputStream(active.socket.getInputStream()));
            byte[] data = null;
            while (running) {
                int length = in.readInt();
                if (length == 0) continue; // keep-alive while the computer screen is idle
                if (length < 0 || length > MAX_UNIT_BYTES) throw new IOException("bad unit length");
                if (data == null || data.length < length) data = new byte[length];
                in.readFully(data, 0, length);
                boolean queued = false;
                while (running && !queued) {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null) throw new IOException("no input buffer");
                        buffer.clear();
                        buffer.put(data, 0, length);
                        decoder.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0);
                        queued = true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error en la pantalla: " + e.getMessage(), e);
        } finally {
            releaseCodec();
            releaseConnection();
            boolean shouldFinish = running;
            running = false;
            if (shouldFinish && !userAskedToClose) {
                closedByItself = true;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && !isDestroyed()) finish();
                    }
                });
            }
        }
    }

    private void drainOutput(MediaCodec decoder, MediaCodec.BufferInfo info, long timeoutUs) {
        while (true) {
            int index = decoder.dequeueOutputBuffer(info, timeoutUs);
            timeoutUs = 0;
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true);
                framesShown++;
                if (framesShown % 100 == 0) {
                    Log.d(TAG, "Cuadros mostrados: " + framesShown);
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Formato de salida: " + decoder.getOutputFormat());
            } else {
                break;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec decoder;
        synchronized (codecLock) {
            decoder = codec;
            codec = null;
        }
        if (decoder == null) return;
        try {
            decoder.stop();
        } catch (Exception error) {
            Log.w(TAG, "No se pudo detener el decodificador: " + error.getMessage(), error);
        }
        try {
            decoder.release();
        } catch (Exception error) {
            Log.w(TAG, "No se pudo liberar el decodificador: " + error.getMessage(), error);
        }
    }

    private void releaseConnection() {
        ScreenReceiver.Connection active;
        synchronized (connectionLock) {
            active = connection;
            connection = null;
        }
        if (active != null) ScreenReceiver.releaseConnection(active);
    }

    private void stopStream() {
        running = false;
        releaseCodec();
        releaseConnection();
        Thread thread = decodeThread;
        decodeThread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    @Override public void onBackPressed() {
        userAskedToClose = true;
        stopStream();
        finish();
    }

    @Override protected void onStop() {
        if (!isFinishing() && !isDestroyed()) userAskedToClose = true;
        stopStream();
        super.onStop();
        if (!isFinishing() && !isDestroyed()) finish();
    }

    @Override protected void onDestroy() {
        stopStream();
        if (!closedByItself) PcControl.apagar(this);
        if (hint != null) {
            hint.animate().cancel();
            hint = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
