package com.cardlens.live;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
    private final Context context;
    private final WindowManager windowManager;
    private LinearLayout root;
    private WindowManager.LayoutParams params;
    private TextView title;
    private TextView subtitle;
    private TextView market;
    private TextView buys;
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
        title.setText("CARDLENS LIVE");
        subtitle.setText("Watching for a stable card number…");
        market.setText("Whatnot live scan");
        buys.setText("");
        status.setText("ON-DEVICE OCR");
    }

    public void showCard(MarketCard card) {
        if (!canShow()) return;
        ensureCreated();
        title.setText(card.name());
        subtitle.setText(card.setName() + "  •  #" + card.number() +
                (card.rarity().trim().isEmpty() ? "" : "  •  " + card.rarity()));
        market.setText(card.marketLabel());
        buys.setText("80%  " + card.maxBidLabel(.80) + "     70%  " + card.maxBidLabel(.70));
        String confidence = String.format(Locale.US, "MATCH %.0f%%", card.confidence() * 100);
        if (!card.priceUpdatedAt().trim().isEmpty()) confidence += "  •  PRICE " + card.priceUpdatedAt();
        status.setText(confidence);
    }

    public void showMessage(String message) {
        if (!canShow()) return;
        ensureCreated();
        status.setText(message);
    }

    private void ensureCreated() {
        if (root != null) return;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(11), dp(14), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 18, 18, 18));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(110, 255, 255, 255));
        root.setBackground(bg);

        title = label(16, Color.WHITE, true);
        subtitle = label(12, Color.rgb(210, 210, 210), false);
        market = label(18, Color.WHITE, true);
        buys = label(14, Color.rgb(235, 235, 235), true);
        status = label(10, Color.rgb(175, 175, 175), false);

        root.addView(title);
        root.addView(subtitle);
        root.addView(market);
        root.addView(buys);
        root.addView(status);

        params = new WindowManager.LayoutParams(
                dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(10);
        params.y = dp(110);

        root.setOnTouchListener(new DragTouchListener());
        windowManager.addView(root, params);
    }

    private TextView label(float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(2), 0, dp(2));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    public void remove() {
        if (root == null) return;
        try { windowManager.removeView(root); } catch (Exception ignored) {}
        root = null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;

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
