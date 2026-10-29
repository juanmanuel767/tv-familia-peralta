package com.familia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rotates family photos with a soft fade while keeping at most two bitmaps in memory. */
final class PhotoRotator {
    private static final int MAX_SIDE = 1280;
    private static final long FADE_MILLIS = 1000L;
    private static final long KEN_BURNS_MILLIS = 20_000L;

    private final Context context;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final List<File> files = new ArrayList<>();

    private Bitmap current;
    private Bitmap next;
    private File currentFile;
    private File nextFile;
    private int currentIndex = -1;
    private int generation;
    private boolean loadingNext;
    private long fadeStart = -1L;
    private long currentCycleStart = -1L;
    private long nextCycleStart = -1L;
    private boolean finishPosted;
    private boolean disposed;
    private int photosVersion = -1;
    private final Runnable finishFade = new Runnable() {
        @Override public void run() {
            finishPosted = false;
            if (!disposed) completeFade();
        }
    };

    PhotoRotator(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
    }

    static File photoDir(Context context) {
        File directory = new File(context.getFilesDir(), "fotos");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    static List<File> listPhotos(Context context) {
        File[] found = photoDir(context).listFiles();
        List<File> result = new ArrayList<>();
        if (found != null) {
            for (File file : found) {
                if (file.isFile() && isImage(file.getName())) result.add(file);
            }
        }
        result.sort((left, right) -> left.getName().compareTo(right.getName()));
        return result;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    void start() {
        refresh();
    }

    void refresh() {
        if (disposed) return;
        final int request = ++generation;
        decoder.execute(new Runnable() {
            @Override public void run() {
                final List<File> found = listPhotos(context);
                main.post(new Runnable() {
                    @Override public void run() { applyFiles(found, request); }
                });
            }
        });
    }

    private void applyFiles(List<File> found, int request) {
        if (disposed || request != generation) return;
        files.clear();
        files.addAll(found);

        if (files.isEmpty()) {
            recycle(next);
            next = null;
            nextFile = null;
            recycle(current);
            current = null;
            currentFile = null;
            currentIndex = -1;
            fadeStart = -1L;
            currentCycleStart = -1L;
            nextCycleStart = -1L;
            changed.run();
            return;
        }

        if (current == null || currentFile == null || !contains(currentFile)) {
            recycle(next);
            next = null;
            nextFile = null;
            recycle(current);
            current = null;
            currentIndex = -1;
            fadeStart = -1L;
            currentCycleStart = -1L;
            nextCycleStart = -1L;
            loadCurrent(files.get(0), 0, request);
            return;
        }

        currentIndex = files.indexOf(currentFile);
        if (currentIndex < 0) currentIndex = 0;
        if (nextFile != null && !contains(nextFile)) {
            recycle(next);
            next = null;
            nextFile = null;
        }
        if (next == null) loadNext(request);
    }

    private boolean contains(File file) {
        for (File candidate : files) {
            if (candidate.getAbsolutePath().equals(file.getAbsolutePath())) return true;
        }
        return false;
    }

    private void loadCurrent(final File file, final int index, final int request) {
        decoder.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bitmap = decode(file);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (disposed || request != generation) {
                            recycle(bitmap);
                            return;
                        }
                        recycle(current);
                        current = bitmap;
                        currentFile = file;
                        currentIndex = index;
                        fadeStart = -1L;
                        currentCycleStart = SystemClock.uptimeMillis();
                        nextCycleStart = -1L;
                        changed.run();
                        loadNext(request);
                    }
                });
            }
        });
    }

    private void loadNext(final int request) {
        if (disposed || loadingNext || files.size() < 2) return;
        final int index = (currentIndex + 1) % files.size();
        final File file = files.get(index);
        if (currentFile != null && file.getAbsolutePath().equals(currentFile.getAbsolutePath())) return;
        loadingNext = true;
        decoder.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bitmap = decode(file);
                main.post(new Runnable() {
                    @Override public void run() {
                        loadingNext = false;
                        if (disposed || request != generation || bitmap == null) {
                            recycle(bitmap);
                            return;
                        }
                        recycle(next);
                        next = bitmap;
                        nextFile = file;
                    }
                });
            }
        });
    }

    /** Starts a fade when the next photo is ready; called every 20 seconds by the view. */
    void tick() {
        if (disposed) return;
        SharedPreferences prefs = context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE);
        int version = prefs.getInt(HomePrefs.FOTOS_VERSION, 0);
        if (version != photosVersion) {
            photosVersion = version;
            refresh();
        }
        if (current == null) {
            refresh();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (next != null && fadeStart < 0L) {
            fadeStart = now;
            nextCycleStart = now;
            changed.run();
        } else if (next == null) {
            currentCycleStart = now;
            changed.run();
        }
    }

    /** Restarts the slow zoom after the screen was paused or resumed. */
    void resync() {
        if (disposed) return;
        long now = SystemClock.uptimeMillis();
        currentCycleStart = now;
        if (next != null) {
            nextCycleStart = now;
            if (fadeStart >= 0L) fadeStart = now;
        }
    }

    boolean isAnimating() {
        return fadeStart >= 0L && next != null;
    }

    /** Draws the current photo (or the fade between two photos). Returns false when empty. */
    boolean draw(Canvas canvas, int width, int height) {
        if (current == null) return false;
        final long now = SystemClock.uptimeMillis();
        if (next != null && fadeStart >= 0L) {
            long elapsed = now - fadeStart;
            float progress = Math.min(1f, elapsed / (float) FADE_MILLIS);
            canvas.save();
            applyKenBurns(canvas, width, height, kenBurns(now, currentCycleStart), currentIndex);
            drawCover(canvas, current, width, height, 255);
            canvas.restore();
            canvas.save();
            applyKenBurns(canvas, width, height, kenBurns(now, nextCycleStart), currentIndex + 1);
            drawCover(canvas, next, width, height, (int) (255 * progress));
            canvas.restore();
            if (progress >= 1f && !finishPosted) {
                finishPosted = true;
                main.post(finishFade);
            }
        } else {
            canvas.save();
            applyKenBurns(canvas, width, height, kenBurns(now, currentCycleStart), currentIndex);
            drawCover(canvas, current, width, height, 255);
            canvas.restore();
        }
        return true;
    }

    private static float kenBurns(long now, long start) {
        if (start < 0L) return 0f;
        long elapsed = now - start;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= KEN_BURNS_MILLIS) return 1f;
        return elapsed / (float) KEN_BURNS_MILLIS;
    }

    private static void applyKenBurns(Canvas canvas, int width, int height, float progress, int index) {
        final float scale = 1f + 0.08f * progress;
        final float direction = (index & 1) == 0 ? 1f : -1f;
        final float driftX = direction * width * 0.012f * progress;
        final float driftY = -height * 0.008f * progress;
        canvas.translate(width * 0.5f, height * 0.5f);
        canvas.scale(scale, scale);
        canvas.translate(-width * 0.5f + driftX, -height * 0.5f + driftY);
    }

    private void completeFade() {
        Bitmap previous = current;
        current = next;
        currentFile = nextFile;
        next = null;
        nextFile = null;
        currentIndex = currentFile == null ? -1 : files.indexOf(currentFile);
        if (currentIndex < 0) currentIndex = 0;
        currentCycleStart = nextCycleStart >= 0L ? nextCycleStart : SystemClock.uptimeMillis();
        nextCycleStart = -1L;
        fadeStart = -1L;
        recycle(previous);
        loadNext(generation);
        changed.run();
    }

    private void drawCover(Canvas canvas, Bitmap bitmap, int width, int height, int alpha) {
        source.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float drawnWidth = bitmap.getWidth() * scale;
        float drawnHeight = bitmap.getHeight() * scale;
        float x = (width - drawnWidth) / 2f;
        float y = (height - drawnHeight) / 2f;
        destination.set(x, y, x + drawnWidth, y + drawnHeight);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, source, destination, paint);
        paint.setAlpha(255);
    }

    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    void dispose() {
        disposed = true;
        decoder.shutdownNow();
        recycle(current);
        recycle(next);
        current = null;
        next = null;
        files.clear();
    }
}
