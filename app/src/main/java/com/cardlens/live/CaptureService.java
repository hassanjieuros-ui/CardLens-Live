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
 * Frames are processed in memory only. Raw screen images are never saved or transmitted. v0.7
 * adds motion tolerance by rejecting heavily blurred frames, retaining only the clearest recent
 * downscaled frame and fusing OCR evidence across a short rolling window.
 */
public class CaptureService extends Service {
    public static final String ACTION_START = "com.cardlens.live.START";
    public static final String ACTION_STOP = "com.cardlens.live.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "CardLensCapture";
    private static final String CHANNEL = "cardlens_live";
    private static final int NOTIFICATION_ID = 42;

    // Normal scanning is deliberately moderate for thermals. A plausible card starts a short burst
    // so the second confirming read can still land quickly in a five-second auction.
    private static final long BASE_OCR_INTERVAL_MS = 230;
    private static final long BURST_OCR_INTERVAL_MS = 150;
    private static final long RESOLVING_OCR_INTERVAL_MS = 360;
    private static final long CONFIRMED_OCR_INTERVAL_MS = 460;
    private static final long BURST_DURATION_MS = 720;
    private static final long BURST_COOLDOWN_MS = 1350;
    private static final long ACTIVE_EVIDENCE_TTL_MS = 2200;
    private static final long LOOKUP_DEDUP_MS = 5000;
    private static final long AMBIGUOUS_RETRY_MS = 700;
    private static final long FALSE_CANDIDATE_BLOCK_MS = 2200;
    private static final long NETWORK_BACKOFF_MS = 3500;
    private static final long RATE_LIMIT_BACKOFF_MS = 60000;
    private static final int CAPTURE_MAX_WIDTH = 640;
    private static final int MAX_PARALLEL_LOOKUPS = 1;
    private static final int MAX_AMBIGUOUS_ATTEMPTS = 2;
    private static final int WIDE_SCAN_EVERY = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicInteger lookupsInFlight = new AtomicInteger(0);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final PokemonTcgClient client = new PokemonTcgClient();
    private final Map<String, Long> lookupStartedAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> ambiguousAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedCandidates = new ConcurrentHashMap<>();
    private final BestFrameBuffer bestFrames = new BestFrameBuffer();
    private final EvidenceWindow evidence = new EvidenceWindow();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private Executor captureExecutor;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private OverlayController overlay;

    private long lastOcrAt;
    private long lookupBackoffUntil;
    private long lastConfirmedAt;
    private long activeLastSeenAt;
    private long burstUntil;
    private long nextBurstAllowedAt;
    private long lastMotionNoticeAt;
    private String activeKey = "";
    private String shownKey = "";
    private CardNumberParser.Candidate activeCandidate;
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
        if (now < burstUntil) return BURST_OCR_INTERVAL_MS;
        if (!shownKey.isEmpty() && now - lastConfirmedAt < 5000) return CONFIRMED_OCR_INTERVAL_MS;
        return BASE_OCR_INTERVAL_MS;
    }

    private void onLocalFrame(ImageReader reader) {
        long now = SystemClock.elapsedRealtime();
        if (!shownKey.isEmpty() && now - lastConfirmedAt > 4800) shownKey = "";

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

        final long frameAt = SystemClock.elapsedRealtime();
        FrameQuality.Result quality = FrameQuality.analyze(ocrFrame);
        bestFrames.offer(ocrFrame, quality.quality(), frameAt);

        // Do not send a heavily smeared frame through ML Kit. The best-frame buffer still keeps any
        // usable recent frame, so motion can subside briefly and identification can proceed without
        // starting from zero.
        if (!quality.worthOcr()) {
            maybeShowMotionState(frameAt);
            maybeResolveActive(frameAt);
            if (!ocrFrame.isRecycled()) ocrFrame.recycle();
            ocrBusy.set(false);
            return;
        }

        final long ocrStartedAt = SystemClock.elapsedRealtime();
        recognizer.process(InputImage.fromBitmap(ocrFrame, 0))
                .addOnSuccessListener(captureExecutor, result -> {
                    long elapsed = SystemClock.elapsedRealtime() - ocrStartedAt;
                    Log.d(TAG, "OCR " + elapsed + "ms q=" +
                            String.format(java.util.Locale.US, "%.2f", quality.quality()));
                    processOcrText(result.getText(), ocrFrame, quality, frameAt);
                })
                .addOnFailureListener(captureExecutor,
                        e -> Log.w(TAG, "On-device OCR failed", e))
                .addOnCompleteListener(captureExecutor, task -> {
                    if (!ocrFrame.isRecycled()) ocrFrame.recycle();
                    ocrBusy.set(false);
                });
    }

    private void maybeShowMotionState(long now) {
        if (now - lastMotionNoticeAt < 850) return;
        lastMotionNoticeAt = now;
        if (activeCandidate != null || shownKey.isEmpty()) {
            main.post(() -> overlay.showMessage("MOTION BUFFERING"));
        }
    }

    private Bitmap cropForFastOcr(Bitmap bitmap) {
        ocrSequence++;
        boolean wideFallback = ocrSequence % WIDE_SCAN_EVERY == 0;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float leftPct = wideFallback ? 0.00f : 0.06f;
        float rightPct = wideFallback ? 1.00f : 0.94f;
        // The default CardLens overlay occupies the upper portion of the stream. Keep normal OCR
        // below it so the scanner cannot read its own status text; wide fallback still samples the
        // upper card/name area occasionally without showing raw collector fractions in the overlay.
        float topPct = wideFallback ? 0.10f : 0.28f;
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

    private void processOcrText(String text, Bitmap currentFrame,
                                FrameQuality.Result quality, long frameAt) {
        Optional<CardNumberParser.Candidate> parsed = CardNumberParser.parse(text);
        if (parsed.isPresent() && isCandidateBlocked(parsed.get().key(), frameAt)) {
            parsed = Optional.empty();
        }

        String parsedKey = parsed.isPresent() ? parsed.get().key() : "";
        evidence.record(parsedKey, text, quality.quality(), frameAt);

        if (parsed.isPresent()) {
            CardNumberParser.Candidate candidate = parsed.get();
            int candidateVotes = evidence.votes(candidate.key(), frameAt);
            int activeVotes = evidence.votes(activeKey, frameAt);
            boolean activeExpired = activeCandidate == null
                    || frameAt - activeLastSeenAt > ACTIVE_EVIDENCE_TTL_MS;

            if (candidate.key().equals(activeKey)) {
                activeCandidate = candidate;
                activeLastSeenAt = frameAt;
            } else if (activeExpired || candidateVotes > activeVotes) {
                boolean replacingExisting = !activeKey.isEmpty();
                activeCandidate = candidate;
                activeKey = candidate.key();
                activeLastSeenAt = frameAt;

                // If this is a real card transition, don't let the previous card's sharp frame win.
                // Re-offer the current frame immediately so we still retain a good image this cycle.
                if (replacingExisting) {
                    bestFrames.clear();
                    bestFrames.offer(currentFrame, quality.quality(), frameAt);
                }
            }

            if (candidate.key().equals(activeKey)) {
                if (candidateVotes == 1) {
                    startShortBurst(frameAt);
                    main.post(() -> overlay.showMessage(
                            "CANDIDATE #" + activeKey + " — TRACKING"));
                }
                if (!shownKey.isEmpty() && !shownKey.equals(activeKey) && candidateVotes >= 2) {
                    shownKey = "";
                }
            }
        } else if (activeCandidate != null
                && frameAt - activeLastSeenAt > ACTIVE_EVIDENCE_TTL_MS) {
            activeCandidate = null;
            activeKey = "";
        }

        maybeResolveActive(frameAt);
    }

    private boolean isCandidateBlocked(String key, long now) {
        if (key == null || key.isEmpty()) return false;
        Long until = blockedCandidates.get(key);
        if (until == null) return false;
        if (now >= until) {
            blockedCandidates.remove(key);
            return false;
        }
        return true;
    }

    private void dropFalseCandidate(String key) {
        long now = SystemClock.elapsedRealtime();
        blockedCandidates.put(key, now + FALSE_CANDIDATE_BLOCK_MS);
        ambiguousAttempts.remove(key);
        lookupStartedAt.remove(key);

        if (key.equals(activeKey)) {
            activeCandidate = null;
            activeKey = "";
            activeLastSeenAt = 0;
            burstUntil = 0;
            evidence.clear();
            bestFrames.clear();
        }

        main.post(() -> overlay.showMessage("FALSE CANDIDATE DROPPED"));
    }

    private void startShortBurst(long now) {
        if (now < nextBurstAllowedAt) return;
        burstUntil = now + BURST_DURATION_MS;
        nextBurstAllowedAt = now + BURST_COOLDOWN_MS;
    }

    private boolean maybeResolveActive(long now) {
        CardNumberParser.Candidate candidate = activeCandidate;
        String key = activeKey;
        if (candidate == null || key.isEmpty() || key.equals(shownKey)) return false;
        if (isCandidateBlocked(key, now)) return false;
        if (evidence.votes(key, now) < 2) return false;
        if (now < lookupBackoffUntil) return false;
        if (lookupsInFlight.get() >= MAX_PARALLEL_LOOKUPS) return false;

        Long previous = lookupStartedAt.get(key);
        if (previous != null && now - previous < LOOKUP_DEDUP_MS) return false;

        VisualMatcher.LiveSignature visualSignature = bestFrames.createSignature(now);
        if (visualSignature == null || !visualSignature.isUsable()) {
            maybeShowMotionState(now);
            return false;
        }

        String mergedOcr = evidence.mergedText(key, now);
        lookupStartedAt.put(key, now);
        trimLookupHistory(now);
        lookupsInFlight.incrementAndGet();

        main.post(() -> overlay.showMessage(
                "VISUAL MATCH #" + key + " — BEST FRAME"));
        network.submit(() -> lookupCard(candidate, key, mergedOcr, visualSignature));
        return true;
    }

    private void trimLookupHistory(long now) {
        if (lookupStartedAt.size() >= 64) {
            for (Map.Entry<String, Long> entry : lookupStartedAt.entrySet()) {
                if (now - entry.getValue() > 30000) lookupStartedAt.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Long> entry : blockedCandidates.entrySet()) {
            if (now >= entry.getValue()) blockedCandidates.remove(entry.getKey());
        }
    }

    private void lookupCard(CardNumberParser.Candidate candidate, String key, String ocrText,
                            VisualMatcher.LiveSignature visualSignature) {
        long started = SystemClock.elapsedRealtime();
        try {
            MarketCard card = client.lookup(candidate, ocrText, visualSignature);
            long elapsed = SystemClock.elapsedRealtime() - started;
            Log.d(TAG, "Motion-tolerant lookup " + key + " " + elapsed + "ms");

            if (card == null) {
                int attempts = ambiguousAttempts.merge(key, 1, Integer::sum);
                if (attempts >= MAX_AMBIGUOUS_ATTEMPTS) {
                    Log.i(TAG, "Dropping repeatedly unsupported candidate " + key);
                    dropFalseCandidate(key);
                    return;
                }

                // Allow one newer clear-frame attempt before declaring the OCR candidate false.
                lookupStartedAt.put(key,
                        SystemClock.elapsedRealtime() - LOOKUP_DEDUP_MS + AMBIGUOUS_RETRY_MS);
                if (key.equals(activeKey)) {
                    main.post(() -> overlay.showMessage(
                            "AMBIGUOUS #" + key + " — COLLECTING FRAMES"));
                }
                return;
            }

            ambiguousAttempts.remove(key);
            blockedCandidates.remove(key);
            if (key.equals(activeKey)
                    || SystemClock.elapsedRealtime() - activeLastSeenAt <= ACTIVE_EVIDENCE_TTL_MS + 1600) {
                shownKey = key;
                lastConfirmedAt = SystemClock.elapsedRealtime();
                bestFrames.clear();
                main.post(() -> overlay.showCard(card));
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            boolean rateLimited = message.contains("rate limit") || message.contains("429");
            lookupBackoffUntil = SystemClock.elapsedRealtime()
                    + (rateLimited ? RATE_LIMIT_BACKOFF_MS : NETWORK_BACKOFF_MS);
            Log.w(TAG, "Card identification failed for " + key, e);

            if (key.equals(activeKey)) {
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
                .setContentText("Motion-tolerant live card scan is active")
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
        bestFrames.clear();
        evidence.clear();
        ambiguousAttempts.clear();
        blockedCandidates.clear();
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
