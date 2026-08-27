package com.cardlens.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User-consented screen-share service for CardLens Live.
 *
 * Android itself presents the MediaProjection permission dialog before this service can start.
 * Frames are processed only in memory for on-device OCR, are never saved, and raw screen images
 * are never transmitted. Only parsed trading-card identifiers are sent to the public card API.
 */
public class CaptureService extends Service {
    public static final String ACTION_START = "com.cardlens.live.START";
    public static final String ACTION_STOP = "com.cardlens.live.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "CardLensCapture";
    private static final String CHANNEL = "cardlens_live";
    private static final int NOTIFICATION_ID = 42;

    // v0.3 sudden-death mode: optimize for a useful answer inside a five-second auction.
    private static final long OCR_INTERVAL_MS = 120;
    private static final long STABILITY_WINDOW_MS = 1100;
    private static final long LOOKUP_DEDUP_MS = 4000;
    private static final int CAPTURE_MAX_WIDTH = 800;
    private static final int MAX_PARALLEL_LOOKUPS = 2;
    private static final int WIDE_SCAN_EVERY = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicInteger lookupsInFlight = new AtomicInteger(0);
    private final ExecutorService network = Executors.newFixedThreadPool(MAX_PARALLEL_LOOKUPS);
    private final PokemonTcgClient client = new PokemonTcgClient();
    private final Map<String, Long> lookupStartedAt = new ConcurrentHashMap<>();
    private final Map<String, MarketCard> cache = new LinkedHashMap<String, MarketCard>(48, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MarketCard> eldest) {
            return size() > 160;
        }
    };

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private OverlayController overlay;

    private long lastOcrAt;
    private long pendingLastSeenAt;
    private String pendingKey = "";
    private int pendingHits;
    private String shownKey = "";
    private int ocrSequence;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        overlay = new OverlayController(this);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        captureThread = new HandlerThread("CardLensLocalFrames");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent consentData = getParcelableIntent(intent, EXTRA_RESULT_DATA);
        if (resultCode == 0 || consentData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startProjectionForeground();
        startUserApprovedProjection(resultCode, consentData);
        main.post(() -> overlay.showScanning());
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getParcelableIntent(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(key, Intent.class);
        return intent.getParcelableExtra(key);
    }

    private void startProjectionForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startUserApprovedProjection(int resultCode, Intent consentData) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, consentData);
        if (projection == null) {
            main.post(() -> overlay.showMessage("SCREEN SHARE NOT AVAILABLE"));
            stopSelf();
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopSelf();
            }
        }, main);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sourceWidth = Math.max(dm.widthPixels, 720);
        int sourceHeight = Math.max(dm.heightPixels, 1280);
        int width = Math.min(CAPTURE_MAX_WIDTH, sourceWidth);
        int height = Math.max(1, Math.round(sourceHeight * (width / (float) sourceWidth)));

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onLocalFrame, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "CardLensUserShare",
                width,
                height,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
    }

    private void onLocalFrame(ImageReader reader) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOcrAt < OCR_INTERVAL_MS || !ocrBusy.compareAndSet(false, true)) {
            Image skipped = reader.acquireLatestImage();
            if (skipped != null) skipped.close();
            return;
        }
        lastOcrAt = now;

        Image image = reader.acquireLatestImage();
        if (image == null) {
            ocrBusy.set(false);
            return;
        }

        Bitmap fullFrame;
        try {
            fullFrame = imageToBitmap(image);
        } catch (Throwable t) {
            Log.w(TAG, "Local frame conversion failed", t);
            image.close();
            ocrBusy.set(false);
            return;
        }
        image.close();

        Bitmap ocrFrame = cropForFastOcr(fullFrame);
        if (ocrFrame != fullFrame) fullFrame.recycle();

        final long ocrStartedAt = SystemClock.elapsedRealtime();
        recognizer.process(InputImage.fromBitmap(ocrFrame, 0))
                .addOnSuccessListener(result -> {
                    long elapsed = SystemClock.elapsedRealtime() - ocrStartedAt;
                    Log.d(TAG, "OCR " + elapsed + "ms");
                    processOcrText(result.getText());
                })
                .addOnFailureListener(e -> Log.w(TAG, "On-device OCR failed", e))
                .addOnCompleteListener(task -> {
                    ocrFrame.recycle();
                    ocrBusy.set(false);
                });
    }

    private Bitmap cropForFastOcr(Bitmap bitmap) {
        ocrSequence++;
        boolean wideFallback = ocrSequence % WIDE_SCAN_EVERY == 0;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Most Whatnot sellers hold the card near center. Five scans out of six use a tighter
        // card region for speed; every sixth scan widens out so off-center cards are still found.
        float leftPct = wideFallback ? 0.00f : 0.08f;
        float rightPct = wideFallback ? 1.00f : 0.92f;
        float topPct = wideFallback ? 0.03f : 0.04f;
        float bottomPct = wideFallback ? 0.82f : 0.76f;

        int left = Math.max(0, Math.round(width * leftPct));
        int top = Math.max(0, Math.round(height * topPct));
        int right = Math.min(width, Math.round(width * rightPct));
        int bottom = Math.min(height, Math.round(height * bottomPct));
        int cropWidth = right - left;
        int cropHeight = bottom - top;

        if (cropWidth < width / 2 || cropHeight < height / 2) return bitmap;
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void processOcrText(String text) {
        final long now = SystemClock.elapsedRealtime();
        Optional<CardNumberParser.Candidate> parsed = CardNumberParser.parse(text);

        if (parsed.isEmpty()) {
            // One blurry video frame does not invalidate a good read from a moment earlier.
            if (now - pendingLastSeenAt > STABILITY_WINDOW_MS) {
                pendingKey = "";
                pendingHits = 0;
            }
            return;
        }

        CardNumberParser.Candidate candidate = parsed.get();
        String key = candidate.key();

        // Already-resolved cards are instant from the in-memory session cache.
        MarketCard cached = getCached(key);
        if (cached != null && !key.equals(shownKey)) {
            shownKey = key;
            pendingKey = key;
            pendingHits = 2;
            pendingLastSeenAt = now;
            main.post(() -> overlay.showCard(cached));
            return;
        }

        if (key.equals(pendingKey) && now - pendingLastSeenAt <= STABILITY_WINDOW_MS) {
            pendingHits++;
        } else {
            pendingKey = key;
            pendingHits = 1;
        }
        pendingLastSeenAt = now;

        // Critical v0.3 change: start pricing on the FIRST plausible OCR hit. Verification and
        // network lookup now happen in parallel instead of serially.
        maybeStartLookup(candidate, text);

        if (pendingHits == 1) {
            main.post(() -> overlay.showMessage("FAST LOOKUP #" + key + " — VERIFYING"));
            return;
        }

        if (key.equals(shownKey)) return;

        // The speculative lookup may already have completed while OCR was confirming the card.
        cached = getCached(key);
        if (cached != null) {
            shownKey = key;
            MarketCard finalCached = cached;
            main.post(() -> overlay.showCard(finalCached));
        } else {
            main.post(() -> overlay.showMessage("CONFIRMED #" + key + " — PRICING"));
        }
    }

    private MarketCard getCached(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private void maybeStartLookup(CardNumberParser.Candidate candidate, String ocrText) {
        String key = candidate.key();
        if (getCached(key) != null) return;

        long now = SystemClock.elapsedRealtime();
        Long previous = lookupStartedAt.get(key);
        if (previous != null && now - previous < LOOKUP_DEDUP_MS) return;
        if (lookupsInFlight.get() >= MAX_PARALLEL_LOOKUPS) return;

        lookupStartedAt.put(key, now);
        int active = lookupsInFlight.incrementAndGet();
        if (active > MAX_PARALLEL_LOOKUPS) {
            lookupsInFlight.decrementAndGet();
            lookupStartedAt.remove(key);
            return;
        }

        String ocrSnapshot = ocrText;
        network.submit(() -> lookupCard(candidate, ocrSnapshot));
    }

    private void lookupCard(CardNumberParser.Candidate candidate, String ocrText) {
        String key = candidate.key();
        long started = SystemClock.elapsedRealtime();
        try {
            MarketCard card = client.lookup(candidate, ocrText);
            long elapsed = SystemClock.elapsedRealtime() - started;
            Log.d(TAG, "Lookup " + key + " " + elapsed + "ms");

            if (card == null) {
                // A speculative first-frame lookup can be ambiguous because OCR had little name
                // context. Let a later confirmed frame retry immediately with better text.
                lookupStartedAt.remove(key);
                if (key.equals(pendingKey) && pendingHits >= 2) {
                    main.post(() -> overlay.showMessage("AMBIGUOUS #" + key + " — HOLD STEADY"));
                }
                return;
            }

            synchronized (cache) {
                cache.put(key, card);
            }

            if (key.equals(pendingKey)
                    && pendingHits >= 2
                    && SystemClock.elapsedRealtime() - pendingLastSeenAt <= STABILITY_WINDOW_MS) {
                shownKey = key;
                main.post(() -> overlay.showCard(card));
            }
        } catch (Exception e) {
            lookupStartedAt.remove(key);
            Log.w(TAG, "Card lookup failed for " + key, e);
            if (key.equals(pendingKey) && pendingHits >= 2) {
                main.post(() -> overlay.showMessage("PRICE LOOKUP RETRYING"));
            }
        } finally {
            lookupsInFlight.decrementAndGet();
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("CardLens Live")
                .setContentText("User-approved sudden-death card scan is active")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Live card scanning", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while your approved CardLens screen share is active.");
        nm.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (recognizer != null) recognizer.close();
        if (overlay != null) main.post(() -> overlay.remove());
        if (captureThread != null) captureThread.quitSafely();
        network.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
