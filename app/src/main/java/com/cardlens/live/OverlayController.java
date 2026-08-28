package com.cardlens.live;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class OverlayController {
    private static final int SURFACE = Color.rgb(18, 22, 27);
    private static final int BORDER = Color.rgb(48, 56, 66);
    private static final int TEXT = Color.WHITE;
    private static final int SECONDARY = Color.rgb(178, 186, 197);
    private static final int MUTED = Color.rgb(116, 126, 139);
    private static final int BLUE = Color.rgb(87, 166, 255);
    private static final int GREEN = Color.rgb(53, 208, 127);
    private static final int YELLOW = Color.rgb(247, 201, 72);
    private static final int RED = Color.rgb(255, 92, 92);

    private final Context context;
    private final WindowManager windowManager;

    private LinearLayout root;
    private WindowManager.LayoutParams params;
    private TextView title;
    private TextView subtitle;
    private TextView price;
    private TextView priceCaption;
    private TextView buyTarget;
    private TextView status;

    public OverlayController(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canShow() {
        return Settings.canDrawOverlays(context);
    }

    public void showScanning() {
        if (!canShow()) return;
        ensureCreated();
        setCompact(true);
        title.setText("Scanning…");
        subtitle.setText("Watching for the clearest card frame");
        priceCaption.setText("BEST-FRAME BUFFER ACTIVE");
        status.setText("MOTION TOLERANT • SUDDEN DEATH");
        status.setTextColor(BLUE);
    }

    public void showCard(MarketCard card) {
        if (!canShow()) return;
        ensureCreated();
        setCompact(false);

        title.setText(card.name());
        subtitle.setText(card.setName() + "  •  #" + card.number());

        if (card.hasPrice()) {
            price.setText(marketValueLabel(card));
            priceCaption.setText(card.priceVariants() > 1 ? "TCG MARKET RANGE" : "TCG MARKET");
            buyTarget.setText("BUY ≤ " + card.maxBidLabel(.80));
        } else {
            price.setText("—");
            priceCaption.setText("MARKET UNAVAILABLE");
            buyTarget.setText("CARD IDENTIFIED");
        }

        String confidence = String.format(Locale.US, "%.0f%% MATCH", card.confidence() * 100);
        if (card.hasPrice()) confidence += "  •  70% " + card.maxBidLabel(.70);
        status.setText(confidence);
        status.setTextColor(card.confidence() >= .85 ? GREEN : YELLOW);
    }

    public void showMessage(String message) {
        if (!canShow()) return;
        ensureCreated();
        if (message == null) message = "";

        if (message.startsWith("MOTION BUFFERING")) {
            setCompact(true);
            title.setText("Tracking card");
            subtitle.setText("Movement detected • saving the sharpest frame");
            priceCaption.setText("SKIPPING BLURRED FRAMES");
            status.setText("MOTION TOLERANCE");
            status.setTextColor(BLUE);
            return;
        }

        if (message.startsWith("CANDIDATE")) {
            setCompact(true);
            title.setText("Possible card");
            subtitle.setText("Building a multi-frame read");
            priceCaption.setText("COLLECTING CLEAR EVIDENCE");
            status.setText("TRACKING MOTION");
            status.setTextColor(YELLOW);
            return;
        }

        if (message.startsWith("VISUAL MATCH") || message.startsWith("IDENTIFYING")) {
            setCompact(true);
            title.setText("Matching artwork");
            subtitle.setText("Using the clearest recent frame");
            priceCaption.setText("ILLUSTRATION + MULTI-FRAME TEXT");
            status.setText("BEST FRAME");
            status.setTextColor(BLUE);
            return;
        }

        if (message.startsWith("AMBIGUOUS")) {
            setCompact(true);
            title.setText("Collecting more frames");
            subtitle.setText("The artwork is not clear enough yet");
            priceCaption.setText("NO PRICE SHOWN");
            status.setText("LOW CONFIDENCE");
            status.setTextColor(YELLOW);
            return;
        }

        if (message.startsWith("FALSE CANDIDATE DROPPED")) {
            setCompact(true);
            title.setText("Scanning…");
            subtitle.setText("Bad read ignored • looking again");
            priceCaption.setText("FALSE MATCH CLEARED");
            status.setText("READY");
            status.setTextColor(BLUE);
            return;
        }

        if (message.startsWith("CARD DATA RATE LIMITED")) {
            setCompact(true);
            title.setText("Card data temporarily limited");
            subtitle.setText("Scanner is cooling down its requests");
            priceCaption.setText("LOCAL SCAN STILL ACTIVE");
            status.setText("DATA BACKOFF");
            status.setTextColor(YELLOW);
            return;
        }

        if (message.contains("RETRYING")) {
            setCompact(true);
            title.setText("Identification retrying");
            subtitle.setText("Keeping the best recent card frame");
            priceCaption.setText("WAITING FOR CARD DATA");
            status.setText("RETRYING");
            status.setTextColor(YELLOW);
            return;
        }

        if (message.contains("SCREEN SHARE")) {
            setCompact(true);
            title.setText("Screen share unavailable");
            subtitle.setText("Restart CardLens and approve sharing");
            priceCaption.setText("SCANNER PAUSED");
            status.setText("ACTION NEEDED");
            status.setTextColor(RED);
            return;
        }

        setCompact(true);
        status.setText(message);
        status.setTextColor(SECONDARY);
    }

    private void setCompact(boolean compact) {
        price.setVisibility(compact ? View.GONE : View.VISIBLE);
        buyTarget.setVisibility(compact ? View.GONE : View.VISIBLE);
        priceCaption.setVisibility(View.VISIBLE);
    }

    private String marketValueLabel(MarketCard card) {
        if (!card.hasPrice()) return "—";
        if (card.priceVariants() <= 1 || Math.abs(card.marketHigh() - card.marketLow()) < .01) {
            return String.format(Locale.US, "$%.2f", card.marketHigh());
        }
        return String.format(Locale.US, "$%.0f–$%.0f", card.marketLow(), card.marketHigh());
    }

    private void ensureCreated() {
        if (root != null) return;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(13));
        root.setBackground(rounded(SURFACE, BORDER, 16));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = label("CARDLENS", 10.5f, TEXT, true);
        header.addView(brand, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView live = label("● LIVE", 10, GREEN, true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(9), dp(4), dp(9), dp(4));
        live.setBackground(rounded(Color.rgb(26, 49, 39), Color.rgb(44, 93, 68), 999));
        header.addView(live);
        root.addView(header, matchWrap());

        title = label("Scanning…", 18, TEXT, true);
        title.setPadding(0, dp(9), 0, 0);
        root.addView(title, matchWrap());

        subtitle = label("Watching the auction", 11.5f, SECONDARY, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        root.addView(subtitle, matchWrap());

        price = label("—", 31, TEXT, true);
        price.setPadding(0, dp(8), 0, 0);
        root.addView(price, matchWrap());

        priceCaption = label("BEST-FRAME BUFFER ACTIVE", 9.5f, MUTED, true);
        priceCaption.setPadding(0, dp(5), 0, dp(5));
        root.addView(priceCaption, matchWrap());

        buyTarget = label("", 15.5f, GREEN, true);
        root.addView(buyTarget, matchWrap());

        status = label("MOTION TOLERANT • SUDDEN DEATH", 9.5f, BLUE, true);
        status.setPadding(0, dp(5), 0, 0);
        root.addView(status, matchWrap());

        params = new WindowManager.LayoutParams(
                dp(292),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(10);
        params.y = dp(82);

        root.setOnTouchListener(new DragTouchListener());
        windowManager.addView(root, params);
        setCompact(true);
    }

    private TextView label(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public void remove() {
        if (root == null) return;
        try {
            windowManager.removeView(root);
        } catch (Exception ignored) {
        }
        root = null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float downX;
        private float downY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX - Math.round(event.getRawX() - downX);
                    params.y = startY + Math.round(event.getRawY() - downY);
                    windowManager.updateViewLayout(root, params);
                    return true;
                default:
                    return false;
            }
        }
    }
}
