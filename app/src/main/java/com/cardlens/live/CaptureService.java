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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User-consented screen-share service for CardLens Live.
 *
 * Frames are processed in memory only. Raw screen images are never saved or transmitted. OCR and
 * live illustration fingerprints are produced on-device; only parsed card identifiers are sent to
 * the card-data provider and official candidate images are downloaded for local comparison.
 */
public class CaptureService extends Service {
    public static final String ACTION_START = "com.cardlens.live.START";
    public static final String ACTION_STOP = "com.cardlens.live.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "CardLensCapture";
    private static final String CHANNEL = "cardlens_live";
    private static final int NOTIFICATION_ID = 42;

    // Fast enough for five-second auctions without running ML Kit flat-out continuously.
    private static final long FAST_OCR_INTERVAL_MS = 190;
    private static final long RESOLVING_OCR_INTERVAL_MS = 360;
    private static final long CONFIRMED_OCR_INTERVAL_MS = 420;
    private static final long STABILITY_WINDOW_MS = 1500;
    private static final long LOOKUP_DEDUP_MS = 5000;
    private static final long NETWORK_BACKOFF_MS = 3500;
    private static final long RATE_LIMIT_BACKOFF_MS = 60000;
    private static final int CAPTURE_MAX_WIDTH = 640;
    private static final int MAX_PARALLEL_LOOKUPS = 1;
    private static final int WIDE_SCAN_EVERY = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicInteger lookupsInFlight = new AtomicInteger(0);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final PokemonTcgClient client = new PokemonTcgClient();
    private final Map<String, Long> lookupStartedAt = new ConcurrentHashMap<>();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private Executor captureExecutor;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private OverlayController overlay;

    private long lastOcrAt;
    private long pendingLastSeenAt;
    private long lookupBackoffUntil;
    private long lastConfirmedAt;
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
        captureExecutor = command -> captureHandler.post(command);
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
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startUserApprovedProjection(int resultCode, Intent consentData) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
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

    private long currentOcrInterval(long now) {
        if (lookupsInFlight.get() > 0) return RESOLVING_OCR_INTERVAL_MS;
        if (!shownKey.isEmpty() && now - lastConfirmedAt < 5000) return CONFIRMED_OCR_INTERVAL_MS;
        return FAST_OCR_INTERVAL_MS;
    }

    private void onLocalFrame(ImageReader reader) {
        long now = SystemClock.elapsedRealtime();
        long interval = currentOcrInterval(now);
        if (now - lastOcrAt < interval || !ocrBusy.compareAndSet(false, true)) {
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
                .addOnSuccessListener(captureExecutor, result -> {
                    long elapsed = SystemClock.elapsedRealtime() - ocrStartedAt;
                    Log.d(TAG, "OCR " + elapsed + "ms");
                    processOcrText(result.getText(), ocrFrame);
                })
                .addOnFailureListener(captureExecutor,
                        e -> Log.w(TAG, "On-device OCR failed", e))
                .addOnCompleteListener(captureExecutor, task -> {
                    if (!ocrFrame.isRecycled()) ocrFrame.recycle();
                    ocrBusy.set(false);
                });
    }

    private Bitmap cropForFastOcr(Bitmap bitmap) {
        ocrSequence++;
        boolean wideFallback = ocrSequence % WIDE_SCAN_EVERY == 0;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // The overlay normally sits near the top. Most passes exclude much of that area so ML Kit
        // spends its budget on the actual stream/card instead of repeatedly OCRing CardLens itself.
        float leftPct = wideFallback ? 0.00f : 0.06f;
        float rightPct = wideFallback ? 1.00f : 0.94f;
        float topPct = wideFallback ? 0.08f : 0.22f;
        float bottomPct = wideFallback ? 0.88f : 0.86f;

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

        Bitmap padded = Bitmap.createBitmap(
                paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == image.getWidth()) return padded;

        Bitmap cropped = Bitmap.createBitmap(
                padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void processOcrText(String text, Bitmap currentFrame) {
        final long now = SystemClock.elapsedRealtime();
        Optional<CardNumberParser.Candidate> parsed = CardNumberParser.parse(text);

        if (parsed.isEmpty()) {
            if (now - pendingLastSeenAt > STABILITY_WINDOW_MS) {
                pendingKey = "";
                pendingHits = 0;
            }
            return;
        }

        CardNumberParser.Candidate candidate = parsed.get();
        String key = candidate.key();

        // A different collector number means a new card/auction and restores the fast cadence.
        if (!shownKey.isEmpty() && !shownKey.equals(key)) shownKey = "";

        if (key.equals(pendingKey) && now - pendingLastSeenAt <= STABILITY_WINDOW_MS) {
            pendingHits++;
        } else {
            pendingKey = key;
            pendingHits = 1;
        }
        pendingLastSeenAt = now;

        if (key.equals(shownKey)) return;

        // First hit is deliberately cheap. At ~190 ms cadence, a second good read is normally only
        // a fraction of a second away, but this gate prevents noisy OCR from starting image/network
        // work several times per second and dramatically reduces heat/API pressure.
        if (pendingHits == 1) {
            main.post(() -> overlay.showMessage("CANDIDATE #" + key + " — VERIFYING"));
            return;
        }

        boolean lookupStarted = maybeStartLookup(candidate, text, currentFrame);
        if (lookupStarted) {
            main.post(() -> overlay.showMessage("VISUAL MATCH #" + key + " — IDENTIFYING"));
        } else if (lookupsInFlight.get() > 0) {
            main.post(() -> overlay.showMessage("IDENTIFYING #" + key));
        }
    }

    private boolean maybeStartLookup(CardNumberParser.Candidate candidate, String ocrText,
                                     Bitmap currentFrame) {
        String key = candidate.key();
        long now = SystemClock.elapsedRealtime();

        if (now < lookupBackoffUntil) return false;
        Long previous = lookupStartedAt.get(key);
        if (previous != null && now - previous < LOOKUP_DEDUP_MS) return false;
        if (lookupsInFlight.get() >= MAX_PARALLEL_LOOKUPS) return false;

        // Artwork fingerprinting is the expensive local step. Do it only after a stable candidate
        // exists, never on every OCR frame.
        VisualMatcher.LiveSignature visualSignature = VisualMatcher.fromLiveFrame(currentFrame);
        if (!visualSignature.isUsable()) return false;

        lookupStartedAt.put(key, now);
        trimLookupHistory(now);
        lookupsInFlight.incrementAndGet();
        String ocrSnapshot = ocrText;
        network.submit(() -> lookupCard(candidate, ocrSnapshot, visualSignature));
        return true;
    }

    private void trimLookupHistory(long now) {
        if (lookupStartedAt.size() < 64) return;
        for (Map.Entry<String, Long> entry : lookupStartedAt.entrySet()) {
            if (now - entry.getValue() > 30000) lookupStartedAt.remove(entry.getKey());
        }
    }

    private void lookupCard(CardNumberParser.Candidate candidate, String ocrText,
                            VisualMatcher.LiveSignature visualSignature) {
        String key = candidate.key();
        long started = SystemClock.elapsedRealtime();
        try {
            MarketCard card = client.lookup(candidate, ocrText, visualSignature);
            long elapsed = SystemClock.elapsedRealtime() - started;
            Log.d(TAG, "Hybrid lookup " + key + " " + elapsed + "ms");

            if (card == null) {
                if (key.equals(pendingKey)) {
                    main.post(() -> overlay.showMessage(
                            "AMBIGUOUS #" + key + " — HOLD STEADY"));
                }
                return;
            }

            // Strong artwork evidence can resolve on the same verified candidate without waiting
            // for another OCR cycle. Otherwise two OCR hits are already present by construction.
            if (key.equals(pendingKey)
                    && SystemClock.elapsedRealtime() - pendingLastSeenAt <= STABILITY_WINDOW_MS + 1200) {
                shownKey = key;
                lastConfirmedAt = SystemClock.elapsedRealtime();
                main.post(() -> overlay.showCard(card));
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            boolean rateLimited = message.contains("rate limit") || message.contains("429");
            lookupBackoffUntil = SystemClock.elapsedRealtime()
                    + (rateLimited ? RATE_LIMIT_BACKOFF_MS : NETWORK_BACKOFF_MS);
            Log.w(TAG, "Card identification failed for " + key, e);

            if (key.equals(pendingKey)) {
                if (rateLimited) {
                    main.post(() -> overlay.showMessage("CARD DATA RATE LIMITED"));
                } else {
                    main.post(() -> overlay.showMessage("IDENTIFICATION RETRYING"));
                }
            }
        } finally {
            lookupsInFlight.decrementAndGet();
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(
                this, 2, new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("CardLens Live")
                .setContentText("Efficient live illustration matching is active")
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

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
