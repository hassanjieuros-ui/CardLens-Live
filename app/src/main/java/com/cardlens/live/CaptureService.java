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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-consented screen-share service for CardLens Live.
 *
 * Android itself presents the MediaProjection permission dialog before this service can start.
 * Frames are processed only in memory for on-device OCR, are never saved, and raw screen images
 * are never transmitted. Only the parsed trading-card lookup is sent to the public card API.
 */
public class CaptureService extends Service {
    public static final String ACTION_START = "com.cardlens.live.START";
    public static final String ACTION_STOP = "com.cardlens.live.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "CardLensCapture";
    private static final String CHANNEL = "cardlens_live";
    private static final int NOTIFICATION_ID = 42;
    private static final long OCR_INTERVAL_MS = 800;
    private static final long API_MIN_INTERVAL_MS = 1600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final PokemonTcgClient client = new PokemonTcgClient();
    private final Map<String, MarketCard> cache = new LinkedHashMap<String, MarketCard>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MarketCard> eldest) {
            return size() > 100;
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
    private long lastApiAt;
    private String pendingKey = "";
    private int pendingHits;
    private String shownKey = "";

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
        int width = Math.min(1080, sourceWidth);
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

        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image);
        } catch (Throwable t) {
            Log.w(TAG, "Local frame conversion failed", t);
            image.close();
            ocrBusy.set(false);
            return;
        }
        image.close();

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(result -> processOcrText(result.getText()))
                .addOnFailureListener(e -> Log.w(TAG, "On-device OCR failed", e))
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
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
        Optional<CardNumberParser.Candidate> parsed = CardNumberParser.parse(text);
        if (parsed.isEmpty()) {
            pendingKey = "";
            pendingHits = 0;
            return;
        }

        CardNumberParser.Candidate candidate = parsed.get();
        if (candidate.key().equals(pendingKey)) pendingHits++;
        else {
            pendingKey = candidate.key();
            pendingHits = 1;
        }

        if (pendingHits < 2 || candidate.key().equals(shownKey)) return;

        MarketCard cached;
        synchronized (cache) { cached = cache.get(candidate.key()); }
        if (cached != null) {
            shownKey = candidate.key();
            main.post(() -> overlay.showCard(cached));
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastApiAt < API_MIN_INTERVAL_MS) return;
        lastApiAt = now;
        String ocrSnapshot = text;
        network.submit(() -> lookupCard(candidate, ocrSnapshot));
    }

    private void lookupCard(CardNumberParser.Candidate candidate, String ocrText) {
        try {
            MarketCard card = client.lookup(candidate, ocrText);
            if (card == null) {
                main.post(() -> overlay.showMessage("AMBIGUOUS " + candidate.key() + " — HOLD"));
                return;
            }
            synchronized (cache) { cache.put(candidate.key(), card); }
            shownKey = candidate.key();
            main.post(() -> overlay.showCard(card));
        } catch (Exception e) {
            Log.w(TAG, "Card lookup failed", e);
            main.post(() -> overlay.showMessage("LOOKUP RETRYING"));
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
                .setContentText("User-approved live card scan is active")
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
