package com.familia.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** A dependency-free Android TV home screen and family hub. */
public final class MainActivity extends Activity {
    private FamilyHomeView home;
    private WeatherService weather;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        home = new FamilyHomeView(this);
        setContentView(home);

        weather = new WeatherService(this, new WeatherService.Listener() {
            @Override public void onWeatherUpdated() { refreshHome(false, true, false); }
        });
        weather.start();

        startForegroundService(new Intent(this, HogarService.class));

        prefs = getSharedPreferences(HomePrefs.FILE, MODE_PRIVATE);
        prefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (HomePrefs.MESSAGE.equals(key)) {
                    refreshHome(true, false, false);
                } else if (HomePrefs.CITY.equals(key)) {
                    refreshHome(false, true, false);
                    if (weather != null) weather.requestRefresh();
                }
            }
        };
    }

    @Override protected void onResume() {
        super.onResume();
        BootReceiver.enableHomeButtonService(this);
        if (prefs != null && prefsListener != null) prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        if (home != null) home.resumeAnimations();
    }

    /** This is the TV home screen: Back stays here instead of closing it. */
    @Override public void onBackPressed() { }

    @Override protected void onPause() {
        if (prefs != null && prefsListener != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (home != null) home.pauseAnimations();
        super.onPause();
    }

    private void refreshHome(final boolean message, final boolean weatherText, final boolean photos) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (home == null || isFinishing() || isDestroyed()) return;
                if (message) {
                    home.reloadMessage();
                    home.animateMessage();
                }
                if (weatherText) home.reloadWeather();
                if (photos) home.reloadPhotos();
            }
        });
    }

    @Override protected void onDestroy() {
        if (weather != null) weather.stop();
        if (home != null) home.dispose();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && home != null && home.handleKey(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    private static final int ACTION_OPEN = 0;
    private static final int ACTION_PC_MIRROR = 1;
    private static final int ACTION_PC_EXTRA = 2;

    private static final class AppEntry {
        final String packageName;
        final String fallbackLabel;
        final String shortLabel;
        final Intent intent;
        final Drawable icon;
        final int action;
        final String pcMode;
        AppEntry(String packageName, String fallbackLabel, String shortLabel, Intent intent, Drawable icon) {
            this(packageName, fallbackLabel, shortLabel, intent, icon, ACTION_OPEN, null);
        }
        AppEntry(String packageName, String fallbackLabel, String shortLabel, int action, String pcMode) {
            this(packageName, fallbackLabel, shortLabel, null, null, action, pcMode);
        }
        private AppEntry(String packageName, String fallbackLabel, String shortLabel, Intent intent,
                         Drawable icon, int action, String pcMode) {
            this.packageName = packageName;
            this.fallbackLabel = fallbackLabel;
            this.shortLabel = shortLabel;
            this.intent = intent;
            this.icon = icon;
            this.action = action;
            this.pcMode = pcMode;
        }
    }

    void proyectarPc(final String modo) {
        if (!PcControl.disponible(this)) {
            Toast.makeText(this, "Enciende la computadora: no la encuentro en el wifi", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Conectando con la computadora…", Toast.LENGTH_SHORT).show();
        PcControl.encender(this, modo, new PcControl.Callback() {
            @Override public void onResult(boolean exito) {
                if (!exito) {
                    Toast.makeText(MainActivity.this, "No pude hablar con la computadora", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static final class FamilyHomeView extends View {
        private static final int PINK = Color.rgb(255, 121, 182);
        private static final int BLUE = Color.rgb(82, 177, 255);
        private static final String TITLE = "Familia Peralta";
        private static final Typeface TYPE_BOLD = Typeface.create("sans", Typeface.BOLD);
        private static final Typeface TYPE_NORMAL = Typeface.create("sans", Typeface.NORMAL);
        private static final int PARTICLES = 25;
        private static final long ENTRY_MS = 600L;
        private static final long STAGGER_MS = 80L;
        private static final float RISE_PX = 30f;
        private static final long FRAME_MS = 33L;
        private static final long MESSAGE_INTRO_MS = 500L;
        private static final long KEN_BURNS_MS = 20_000L;

        private final MainActivity activity;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect source = new Rect();
        private final RectF destination = new RectF();
        private final Matrix shineMatrix = new Matrix();
        private final List<AppEntry> apps = new ArrayList<>();
        private final PhotoRotator photos;
        private final String deviceAddress;
        private final String footerText;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es", "CO"));
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", new Locale("es", "CO"));
        private final Date clockDate = new Date();
        private final Random random = new Random();
        private final float[] particleX = new float[PARTICLES];
        private final float[] particleY = new float[PARTICLES];
        private final float[] particleSpeed = new float[PARTICLES];
        private final float[] particlePhase = new float[PARTICLES];
        private final float[] particleAlpha = new float[PARTICLES];
        private final float[] particleRadius = new float[PARTICLES];

        private Bitmap background;
        private String weatherLine = "";
        private String message = "";
        private String messageTime = "";
        private String messageTimeLine = "";
        private String dateLine = "";
        private String hourLine = "00";
        private String minuteLine = "00";
        private String[] messageLines = new String[0];
        private float[] cardScales = new float[0];

        private int selected = 0;
        private int firstVisibleTarget = 0;
        private float firstVisibleFloat = 0f;
        private long entryStart = -1L;
        private long messageIntroStart = -1L;
        private long lastFrameTime = 0L;
        private long now = 0L;
        private boolean running = false;

        private float titleSize;
        private float subtitleSize;
        private float clockSize;
        private float weatherSize;
        private float sectionSize;
        private float cardLabelSize;
        private float footerSize;
        private float messagePadding;
        private float messageTitleSize;
        private float messageBodySize;
        private float messageTimeSize;
        private float messageLineHeight;
        private float titleWidth;
        private float shineWidth;
        private LinearGradient scrimShader;
        private LinearGradient shineShader;

        private final Runnable clockTick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                updateClockText();
                postDelayed(this, 1000L);
            }
        };
        private final Runnable photoTick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                photos.tick();
                postDelayed(this, 20_000L);
            }
        };
        private final Runnable frameTick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                postInvalidateOnAnimation();
                postDelayed(this, FRAME_MS);
            }
        };

        FamilyHomeView(MainActivity activity) {
            super(activity);
            this.activity = activity;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription("Inicio Familia TV. Usa izquierda y derecha para elegir una aplicación y aceptar para abrirla.");
            text.setTypeface(TYPE_NORMAL);
            deviceAddress = findWifiAddress();
            String address = deviceAddress == null ? "sin conexión wifi" : deviceAddress;
            footerText = "Desde tu celular: http://" + address + ":8080";
            photos = new PhotoRotator(activity, new Runnable() {
                @Override public void run() { invalidate(); }
            });
            for (int i = 0; i < PARTICLES; i++) {
                particleX[i] = random.nextFloat();
                particleY[i] = random.nextFloat();
                particleSpeed[i] = 0.008f + random.nextFloat() * 0.012f;
                particlePhase[i] = random.nextFloat() * 6.28318f;
                particleAlpha[i] = 0.12f + random.nextFloat() * 0.28f;
                particleRadius[i] = 0.0012f + random.nextFloat() * 0.0022f;
            }
            loadBackground();
            loadApps();
            reloadMessage();
            reloadWeather();
            updateClockText();
            photos.start();
        }

        void resumeAnimations() {
            if (running) return;
            running = true;
            entryStart = SystemClock.uptimeMillis();
            lastFrameTime = 0L;
            updateClockText();
            removeCallbacks(frameTick);
            removeCallbacks(clockTick);
            removeCallbacks(photoTick);
            photos.resync();
            post(clockTick);
            post(photoTick);
            post(frameTick);
        }

        void pauseAnimations() {
            running = false;
            removeCallbacks(frameTick);
            removeCallbacks(clockTick);
            removeCallbacks(photoTick);
        }

        private void updateClockText() {
            clockDate.setTime(System.currentTimeMillis());
            dateLine = dateFormat.format(clockDate);
            String time = timeFormat.format(clockDate);
            if (time.length() >= 5) {
                hourLine = time.substring(0, 2);
                minuteLine = time.substring(3, 5);
            }
        }

        private void loadBackground() {
            try {
                InputStream stream = activity.getAssets().open("fondo-familiar.jpg");
                background = BitmapFactory.decodeStream(stream);
                stream.close();
            } catch (IOException ignored) { background = null; }
        }

        private void loadApps() {
            final String[][] preferred = new String[][] {
                    {"com.google.android.youtube.tv", "YouTube"},
                    {"com.netflix.ninja", "Netflix"},
                    {"com.disney.disneyplus", "Disney+"},
                    {"com.wbd.stream", "Max"},
                    {"com.apple.atve.androidtv.appletv", "Apple TV"},
                    {"com.mercadolibre.android.mplay_tv", "MPlay"},
                    {"tv.pluto.android", "Pluto TV"},
                    {"com.rcntv.rcntv", "RCN TV"},
                    {"com.iqiyi.i18n.tv", "iQIYI"}
            };
            PackageManager manager = activity.getPackageManager();
            apps.add(new AppEntry("pc-espejo", "Proyectar mi PC", "Proyectar mi PC",
                    ACTION_PC_MIRROR, "espejo"));
            apps.add(new AppEntry("pc-extra", "Pantalla extra", "Pantalla extra",
                    ACTION_PC_EXTRA, "extra"));
            for (String[] entry : preferred) {
                Intent intent = manager.getLeanbackLaunchIntentForPackage(entry[0]);
                if (intent == null) intent = manager.getLaunchIntentForPackage(entry[0]);
                if (intent == null) continue;
                try {
                    ApplicationInfo info = manager.getApplicationInfo(entry[0], 0);
                    CharSequence label = manager.getApplicationLabel(info);
                    String full = label == null ? entry[1] : label.toString();
                    apps.add(new AppEntry(entry[0], full, ellipsize(full, 17), intent, manager.getApplicationIcon(info)));
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            apps.add(new AppEntry("settings", "Ajustes", "Ajustes", new Intent(Settings.ACTION_SETTINGS), null));
            cardScales = new float[apps.size()];
            for (int i = 0; i < cardScales.length; i++) cardScales[i] = 1f;
            selected = 0;
            firstVisibleTarget = 0;
            firstVisibleFloat = 0f;
        }

        void reloadMessage() {
            SharedPreferences prefs = activity.getSharedPreferences(HomePrefs.FILE, Activity.MODE_PRIVATE);
            message = prefs.getString(HomePrefs.MESSAGE, "");
            messageTime = prefs.getString(HomePrefs.MESSAGE_TIME, "");
            messageTimeLine = (messageTime == null || messageTime.isEmpty()) ? "" : "Llegó a las " + messageTime;
            if (getWidth() > 0 && getHeight() > 0) rebuildMessageLines(getWidth(), getHeight());
            invalidate();
        }

        void animateMessage() {
            messageIntroStart = SystemClock.uptimeMillis();
        }

        void reloadWeather() {
            SharedPreferences prefs = activity.getSharedPreferences(HomePrefs.FILE, Activity.MODE_PRIVATE);
            weatherLine = prefs.getString(HomePrefs.WEATHER, "");
            invalidate();
        }

        void reloadPhotos() {
            photos.refresh();
            invalidate();
        }

        void dispose() {
            running = false;
            removeCallbacks(clockTick);
            removeCallbacks(photoTick);
            removeCallbacks(frameTick);
            photos.dispose();
            if (background != null && !background.isRecycled()) background.recycle();
            background = null;
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width <= 0 || height <= 0) return;
            titleSize = Math.max(35f, width * 0.039f);
            subtitleSize = Math.max(17f, width * 0.018f);
            clockSize = Math.max(18f, width * 0.018f);
            weatherSize = Math.max(15f, width * 0.0155f);
            sectionSize = Math.max(22f, width * 0.022f);
            cardLabelSize = Math.max(16f, width * 0.016f);
            footerSize = Math.max(12f, width * 0.0125f);
            messagePadding = Math.max(24f, width * 0.022f);
            messageTitleSize = Math.max(16f, width * 0.017f);
            messageBodySize = Math.max(20f, width * 0.022f);
            messageTimeSize = Math.max(13f, width * 0.0135f);
            messageLineHeight = messageBodySize * 1.25f;
            shineWidth = Math.max(110f, width * 0.15f);
            shineShader = new LinearGradient(0f, 0f, shineWidth, 0f,
                    new int[] {0x00FFFFFF, 0x99FFE6F1, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            scrimShader = new LinearGradient(0f, 0f, 0f, height,
                    new int[] {0xB8000000, 0x36000000, 0xD9000000}, null, Shader.TileMode.CLAMP);
            text.setTypeface(TYPE_BOLD);
            text.setTextSize(titleSize);
            titleWidth = text.measureText(TITLE);
            updateScrollTarget(width);
            rebuildMessageLines(width, height);
        }

        private void rebuildMessageLines(int width, int height) {
            if (message == null || message.trim().isEmpty()) {
                messageLines = new String[0];
                return;
            }
            final float left = width * .065f;
            final float top = height * .235f;
            final float right = left + Math.min(width * .53f, width - left - 40);
            final float bottom = top + height * .335f;
            final float maxWidth = right - left - messagePadding * 2;
            final float textTop = top + messagePadding + messageTitleSize + messageLineHeight * .95f;
            final float maxTextBottom = bottom - messagePadding - messageBodySize * 1.1f;
            final int maxLines = Math.max(1, (int) ((maxTextBottom - textTop) / messageLineHeight) + 1);

            text.setTypeface(TYPE_NORMAL);
            text.setTextSize(messageBodySize);
            ArrayList<String> lines = new ArrayList<>();
            String[] words = message.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (text.measureText(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    if (lines.size() == maxLines - 1) {
                        lines.add(ellipsizeFloat(line.toString() + " " + word, maxWidth));
                        line.setLength(0);
                        break;
                    }
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                    if (lines.size() >= maxLines) break;
                } else {
                    lines.add(ellipsizeFloat(word, maxWidth));
                    if (lines.size() >= maxLines) break;
                }
            }
            if (line.length() > 0 && lines.size() < maxLines) lines.add(line.toString());
            messageLines = lines.toArray(new String[0]);
        }

        private String ellipsizeFloat(String value, float maxWidth) {
            if (text.measureText(value) <= maxWidth) return value;
            String dots = "…";
            int end = value.length();
            while (end > 0 && text.measureText(value.substring(0, end) + dots) > maxWidth) end--;
            return value.substring(0, end) + dots;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final long frameNow = SystemClock.uptimeMillis();
            final long rawDelta = lastFrameTime == 0L ? 16L : frameNow - lastFrameTime;
            final long delta = Math.max(0L, Math.min(100L, rawDelta));
            lastFrameTime = frameNow;
            now = frameNow;

            final int width = getWidth();
            final int height = getHeight();

            updateCardScales(delta);
            updateScroll(delta, width);
            updateParticles(delta);

            drawBackground(canvas, width, height);

            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(scrimShader);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            drawParticles(canvas, width, height);
            drawHeader(canvas, width, height);
            drawMessage(canvas, width, height);
            drawSectionTitle(canvas, width, height);
            drawCards(canvas, width, height);
            drawFooter(canvas, width, height);
        }

        private float appear(int order) {
            if (entryStart < 0L) return 1f;
            long elapsed = now - entryStart - order * STAGGER_MS;
            if (elapsed <= 0L) return 0f;
            if (elapsed >= ENTRY_MS) return 1f;
            float progress = elapsed / (float) ENTRY_MS;
            float inverse = 1f - progress;
            return 1f - inverse * inverse;
        }

        private static int alphaOf(float value) {
            int alpha = (int) (value * 255f);
            if (alpha < 0) return 0;
            if (alpha > 255) return 255;
            return alpha;
        }

        private void drawBackground(Canvas canvas, int width, int height) {
            canvas.drawColor(Color.rgb(18, 21, 31));
            paint.setShader(null);
            paint.setAlpha(255);
            if (photos.draw(canvas, width, height)) return;
            if (background == null) return;
            source.set(0, 0, background.getWidth(), background.getHeight());
            float scale = Math.max(width / (float) background.getWidth(), height / (float) background.getHeight());
            float drawnWidth = background.getWidth() * scale;
            float drawnHeight = background.getHeight() * scale;
            float x = (width - drawnWidth) / 2f;
            float y = (height - drawnHeight) / 2f;
            destination.set(x, y, x + drawnWidth, y + drawnHeight);
            canvas.drawBitmap(background, source, destination, paint);
        }

        private void updateCardScales(long delta) {
            if (cardScales.length == 0) return;
            final float step = 0.10f * delta / 180f;
            for (int i = 0; i < cardScales.length; i++) {
                float target = i == selected ? 1.10f : 1.0f;
                float current = cardScales[i];
                if (current < target) current = Math.min(target, current + step);
                else if (current > target) current = Math.max(target, current - step);
                cardScales[i] = current;
            }
        }

        private int visibleCount(int width) {
            final float left = width * .065f;
            final float cardWidth = Math.min(width * .19f, 250f);
            final float gap = Math.max(18f, width * .018f);
            return Math.max(1, (int) ((width - left - 35f) / (cardWidth + gap)));
        }

        private void updateScrollTarget(int width) {
            if (apps.isEmpty()) {
                firstVisibleTarget = 0;
                return;
            }
            final int visible = visibleCount(width);
            final int maxFirst = Math.max(0, apps.size() - visible);
            if (selected < firstVisibleTarget) firstVisibleTarget = selected;
            if (selected >= firstVisibleTarget + visible) firstVisibleTarget = selected - visible + 1;
            if (firstVisibleTarget > maxFirst) firstVisibleTarget = maxFirst;
            if (firstVisibleTarget < 0) firstVisibleTarget = 0;
        }

        private void updateScroll(long delta, int width) {
            if (apps.isEmpty()) {
                firstVisibleFloat = 0f;
                return;
            }
            final int visible = visibleCount(width);
            final int maxFirst = Math.max(0, apps.size() - visible);
            int target = Math.max(0, Math.min(maxFirst, firstVisibleTarget));
            final float step = delta / 200f;
            if (firstVisibleFloat < target) firstVisibleFloat = Math.min(target, firstVisibleFloat + step);
            else if (firstVisibleFloat > target) firstVisibleFloat = Math.max(target, firstVisibleFloat - step);
        }

        private void updateParticles(long delta) {
            final float seconds = delta / 1000f;
            for (int i = 0; i < PARTICLES; i++) {
                particleY[i] -= particleSpeed[i] * seconds;
                if (particleY[i] < -0.05f) {
                    particleY[i] = 1.05f;
                    particleX[i] = random.nextFloat();
                }
            }
        }

        private void drawParticles(Canvas canvas, int width, int height) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < PARTICLES; i++) {
                float x = particleX[i] * width + (float) Math.sin(now * 0.00035f + particlePhase[i]) * width * 0.006f;
                float y = particleY[i] * height;
                float flicker = 0.70f + 0.30f * (float) Math.sin(now * 0.0011f + particlePhase[i]);
                float value = particleAlpha[i] * flicker;
                if (value > 0.40f) value = 0.40f;
                if (value < 0f) value = 0f;
                paint.setColor((i & 1) == 0 ? 0xFFFFFFFF : 0xFFFFC2DE);
                paint.setAlpha((int) (255f * value));
                canvas.drawCircle(x, y, particleRadius[i] * height, paint);
            }
            paint.setAlpha(255);
        }

        private void drawHeader(Canvas canvas, int width, int height) {
            final float a0 = appear(0);
            if (a0 > 0f) {
                int alpha = alphaOf(a0);
                float titleX = width * .065f;
                float baseline = height * .14f;
                canvas.save();
                canvas.translate(0f, (1f - a0) * RISE_PX);
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(TYPE_BOLD);
                text.setTextSize(titleSize);
                text.setShader(null);
                text.setColor(Color.WHITE);
                text.setAlpha(alpha);
                canvas.drawText(TITLE, titleX, baseline, text);
                if (shineShader != null && titleWidth > 0f) {
                    float cycle = (now % 8000L) / 8000f;
                    float offset = titleX - shineWidth + (titleWidth + shineWidth * 2f) * cycle;
                    shineMatrix.setTranslate(offset, 0f);
                    shineShader.setLocalMatrix(shineMatrix);
                    text.setShader(shineShader);
                    text.setColor(0xFFFFFFFF);
                    text.setAlpha(alpha);
                    canvas.drawText(TITLE, titleX, baseline, text);
                    text.setShader(null);
                }
                text.setAlpha(255);
                canvas.restore();
            }

            final float a1 = appear(1);
            if (a1 > 0f) {
                canvas.save();
                canvas.translate(0f, (1f - a1) * RISE_PX);
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(TYPE_NORMAL);
                text.setTextSize(subtitleSize);
                text.setColor(Color.WHITE);
                text.setAlpha((int) (alphaOf(a1) * 0.90f));
                canvas.drawText("Tu espacio, sin anuncios", width * .067f, height * .19f, text);
                text.setAlpha(255);
                canvas.restore();
            }

            final float a2 = appear(2);
            if (a2 > 0f) {
                int alpha = alphaOf(a2);
                float rightX = width * .935f;
                float baseline = height * .11f;
                canvas.save();
                canvas.translate(0f, (1f - a2) * RISE_PX);
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(TYPE_NORMAL);
                text.setTextSize(clockSize);
                final float dateWidth = text.measureText(dateLine);
                final float separatorWidth = text.measureText("  ·  ");
                final float hourWidth = text.measureText(hourLine);
                final float colonWidth = text.measureText(":");
                final float minuteWidth = text.measureText(minuteLine);
                final float startX = rightX - (dateWidth + separatorWidth + hourWidth + colonWidth + minuteWidth);
                text.setColor(Color.WHITE);
                text.setAlpha(alpha);
                canvas.drawText(dateLine, startX, baseline, text);
                text.setAlpha((int) (alpha * 0.85f));
                canvas.drawText("  ·  ", startX + dateWidth, baseline, text);
                text.setAlpha(alpha);
                canvas.drawText(hourLine, startX + dateWidth + separatorWidth, baseline, text);
                float colonAlpha = alpha * (0.35f + 0.65f * (0.5f + 0.5f * (float) Math.cos(now * 0.006283f)));
                text.setAlpha((int) colonAlpha);
                canvas.drawText(":", startX + dateWidth + separatorWidth + hourWidth, baseline, text);
                text.setAlpha(alpha);
                canvas.drawText(minuteLine, startX + dateWidth + separatorWidth + hourWidth + colonWidth, baseline, text);
                if (weatherLine != null && !weatherLine.isEmpty()) {
                    text.setTextAlign(Paint.Align.RIGHT);
                    text.setColor(0xFFDDE7F5);
                    text.setAlpha((int) (alpha * 0.87f));
                    text.setTextSize(weatherSize);
                    canvas.drawText(weatherLine, rightX, height * .168f, text);
                }
                text.setAlpha(255);
                canvas.restore();
            }
        }

        private void drawMessage(Canvas canvas, int width, int height) {
            if (messageLines.length == 0) return;
            final float entry = appear(3);
            if (entry <= 0f) return;
            float intro = 1f;
            long introElapsed = -1L;
            if (messageIntroStart >= 0L) {
                introElapsed = now - messageIntroStart;
                if (introElapsed >= 0L && introElapsed < MESSAGE_INTRO_MS) {
                    float progress = introElapsed / (float) MESSAGE_INTRO_MS;
                    float inverse = 1f - progress;
                    intro = 1f - inverse * inverse;
                }
            }
            final float alphaValue = entry * intro;
            if (alphaValue <= 0f) return;
            final int alpha = alphaOf(alphaValue);

            final float left = width * .065f - (1f - intro) * 70f;
            final float top = height * .235f;
            final float right = left + Math.min(width * .53f, width - (width * .065f) - 40);
            final float bottom = top + height * .335f;

            canvas.save();
            canvas.translate(0f, (1f - entry) * RISE_PX);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF181E2B);
            paint.setAlpha((int) (alpha * 0.76f));
            canvas.drawRoundRect(left, top, right, bottom, 22, 22, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (alpha * 0.30f));
            canvas.drawRoundRect(left, top, right, bottom, 22, 22, paint);
            paint.setStyle(Paint.Style.FILL);

            float barAlpha = alpha;
            if (introElapsed >= 0L && introElapsed < 1800L) {
                barAlpha *= 0.55f + 0.45f * (float) Math.sin(introElapsed * 0.018f);
            }
            paint.setColor(PINK);
            paint.setAlpha((int) barAlpha);
            canvas.drawRoundRect(left, top, left + 5f, bottom, 3, 3, paint);

            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(TYPE_BOLD);
            text.setColor(PINK);
            text.setTextSize(messageTitleSize);
            text.setAlpha(alpha);
            canvas.drawText("Mensaje para la familia", left + messagePadding,
                    top + messagePadding + messageTitleSize, text);

            text.setTypeface(TYPE_NORMAL);
            text.setColor(Color.WHITE);
            text.setTextSize(messageBodySize);
            text.setAlpha(alpha);
            float textTop = top + messagePadding + messageTitleSize + messageLineHeight * .95f;
            for (int i = 0; i < messageLines.length; i++) {
                canvas.drawText(messageLines[i], left + messagePadding,
                        textTop + i * messageLineHeight, text);
            }

            if (!messageTimeLine.isEmpty()) {
                text.setColor(0xFFD7DEEE);
                text.setTextSize(messageTimeSize);
                text.setAlpha((int) (alpha * 0.80f));
                text.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(messageTimeLine, right - messagePadding, bottom - messagePadding, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            text.setAlpha(255);
            canvas.restore();
        }

        private void drawSectionTitle(Canvas canvas, int width, int height) {
            final float progress = appear(4);
            if (progress <= 0f) return;
            canvas.save();
            canvas.translate(0f, (1f - progress) * RISE_PX);
            text.setTypeface(TYPE_BOLD);
            text.setTextSize(sectionSize);
            text.setColor(Color.WHITE);
            text.setAlpha(alphaOf(progress));
            canvas.drawText("Tus favoritos", width * .067f, height * .62f, text);
            text.setAlpha(255);
            canvas.restore();
        }

        private void drawCards(Canvas canvas, int width, int height) {
            if (apps.isEmpty()) return;
            final float left = width * .065f;
            final float cardWidth = Math.min(width * .19f, 250f);
            final float cardHeight = height * .19f;
            final float gap = Math.max(18f, width * .018f);
            final float y = height * .67f;
            final int visible = visibleCount(width);
            final int start = Math.max(0, (int) Math.floor(firstVisibleFloat));
            final int end = Math.min(apps.size() - 1, start + visible);
            final float pulse = 0.80f + 0.20f * (float) Math.sin(now * 0.0031416f);

            for (int index = start; index <= end; index++) {
                if (index == selected) continue;
                float x = left + (index - firstVisibleFloat) * (cardWidth + gap);
                if (x > width || x + cardWidth < 0f) continue;
                drawCard(canvas, index, x, y, cardWidth, cardHeight, false, pulse);
            }
            if (selected >= start && selected <= end) {
                float x = left + (selected - firstVisibleFloat) * (cardWidth + gap);
                if (x <= width && x + cardWidth >= 0f) {
                    drawCard(canvas, selected, x, y, cardWidth, cardHeight, true, pulse);
                }
            }

            if (firstVisibleFloat > 0.01f || firstVisibleTarget + visible < apps.size()) {
                text.setTypeface(TYPE_NORMAL);
                text.setTextSize(17f);
                text.setColor(Color.WHITE);
                text.setAlpha(0xDD);
                canvas.drawText("←  →", width * .87f, height * .94f, text);
                text.setAlpha(255);
            }
        }

        private void drawCard(Canvas canvas, int index, float x, float y, float cardWidth,
                              float cardHeight, boolean focused, float pulse) {
            final float progress = appear(5 + index);
            if (progress <= 0f) return;
            final int alpha = alphaOf(progress);
            final float scale = cardScales[index];
            final float centerX = x + cardWidth * 0.5f;
            final float centerY = y + cardHeight * 0.5f;

            canvas.save();
            canvas.translate(0f, (1f - progress) * RISE_PX);
            canvas.translate(centerX, centerY);
            canvas.scale(scale, scale);
            canvas.translate(-centerX, -centerY);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(focused ? 0xFFEDF0F5 : 0xFF212734);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(x, y, x + cardWidth, y + cardHeight, 18, 18, paint);
            if (focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5f);
                paint.setColor(PINK);
                paint.setAlpha((int) (alpha * pulse));
                canvas.drawRoundRect(x + 2, y + 2, x + cardWidth - 2, y + cardHeight - 2, 16, 16, paint);
                paint.setStyle(Paint.Style.FILL);
            }

            AppEntry app = apps.get(index);
            if (app.icon != null) {
                int iconSize = (int) Math.min(cardHeight * .42f, 58f);
                int ix = (int) (x + 22);
                int iy = (int) (y + 20);
                app.icon.setBounds(ix, iy, ix + iconSize, iy + iconSize);
                app.icon.setAlpha(alpha);
                app.icon.draw(canvas);
                app.icon.setAlpha(255);
            } else {
                paint.setColor(BLUE);
                paint.setAlpha(alpha);
                canvas.drawRoundRect(x + 23, y + 23, x + 72, y + 72, 13, 13, paint);
            }

            text.setTypeface(TYPE_BOLD);
            text.setTextSize(cardLabelSize);
            text.setColor(focused ? 0xFF131825 : Color.WHITE);
            text.setTextAlign(Paint.Align.LEFT);
            text.setAlpha(alpha);
            canvas.drawText(app.shortLabel, x + 22, y + cardHeight - 24, text);
            text.setAlpha(255);
            canvas.restore();
        }

        private void drawFooter(Canvas canvas, int width, int height) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTypeface(TYPE_NORMAL);
            text.setTextSize(footerSize);
            text.setColor(Color.WHITE);
            text.setAlpha(0xB3);
            canvas.drawText(footerText, width * .965f, height * .965f, text);
            text.setAlpha(255);
            text.setTextAlign(Paint.Align.LEFT);
        }

        private String ellipsize(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
        }

        boolean handleKey(int keyCode) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && selected > 0) {
                selected--;
                updateScrollTarget(getWidth());
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && selected < apps.size() - 1) {
                selected++;
                updateScrollTarget(getWidth());
                invalidate();
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !apps.isEmpty()) {
                AppEntry app = apps.get(selected);
                if (app.action != ACTION_OPEN) {
                    activity.proyectarPc(app.pcMode);
                } else {
                    try { activity.startActivity(app.intent); } catch (Exception ignored) { }
                }
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_MENU;
        }

        private static String findWifiAddress() {
            try {
                List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
                for (NetworkInterface network : interfaces) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (InetAddress address : Collections.list(network.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            String host = address.getHostAddress();
                            if (host != null && !host.isEmpty()) return host;
                        }
                    }
                }
            } catch (Exception ignored) { }
            return null;
        }
    }
}
