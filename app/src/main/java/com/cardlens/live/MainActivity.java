package com.cardlens.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFY = 4102;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) {
            status.setText(Settings.canDrawOverlays(this)
                    ? "Overlay permission: ready"
                    : "Overlay permission: required");
        }
    }

    private View buildUi() {
        int pad = dp(22);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackgroundColor(Color.WHITE);

        TextView title = text("CardLens Live", 30, true, Color.rgb(15, 15, 15));
        TextView copy = text(
                "Live card recognition over Whatnot. The first build is Pokémon-first: screen OCR stays on-device, then the detected collector number is matched to TCGplayer market data.",
                16, false, Color.rgb(70, 70, 70));
        copy.setPadding(0, dp(10), 0, dp(20));

        status = text("", 14, true, Color.rgb(60, 60, 60));
        status.setPadding(0, 0, 0, dp(12));

        Button overlay = button("1. Allow floating overlay");
        overlay.setOnClickListener(v -> openOverlaySettings());

        Button start = button("2. Start Whatnot live scan");
        start.setOnClickListener(v -> startCaptureFlow());

        Button stop = button("Stop live scan");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(intent);
            Toast.makeText(this, "CardLens stopped", Toast.LENGTH_SHORT).show();
        });

        TextView hint = text(
                "Tip: on Android 14+ choose the single-app sharing option and select Whatnot. The floating result card can be dragged around the screen.",
                13, false, Color.rgb(105, 105, 105));
        hint.setPadding(0, dp(18), 0, 0);

        page.addView(title, matchWrap());
        page.addView(copy, matchWrap());
        page.addView(status, matchWrap());
        page.addView(overlay, matchWrapMargins());
        page.addView(start, matchWrapMargins());
        page.addView(stop, matchWrapMargins());
        page.addView(hint, matchWrap());
        return page;
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow the floating overlay first", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not granted", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        Toast.makeText(this, "CardLens is live — open Whatnot", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        return button;
    }

    private TextView text(String value, float sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapMargins() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(7);
        lp.bottomMargin = dp(7);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
