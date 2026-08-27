package com.cardlens.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFY = 4102;

    private static final int BG = Color.rgb(11, 13, 16);
    private static final int SURFACE = Color.rgb(19, 23, 28);
    private static final int SURFACE_RAISED = Color.rgb(26, 32, 39);
    private static final int BORDER = Color.rgb(43, 50, 59);
    private static final int TEXT = Color.WHITE;
    private static final int SECONDARY = Color.rgb(169, 176, 186);
    private static final int MUTED = Color.rgb(111, 119, 130);
    private static final int BLUE = Color.rgb(87, 166, 255);
    private static final int GREEN = Color.rgb(53, 208, 127);
    private static final int YELLOW = Color.rgb(247, 201, 72);

    private TextView overlayStatus;
    private TextView overlayDot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(28));
        page.setBackgroundColor(BG);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("CardLens Live", 28, Typeface.BOLD, TEXT);
        TextView subtitle = text("Real-time card intelligence", 13, Typeface.NORMAL, SECONDARY);
        subtitle.setPadding(0, dp(2), 0, 0);
        brandBlock.addView(title);
        brandBlock.addView(subtitle);
        header.addView(brandBlock, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = pill("v0.4", SURFACE_RAISED, SECONDARY);
        header.addView(version);
        page.addView(header, matchWrap());

        LinearLayout mode = card(SURFACE, BORDER, 16);
        mode.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout modeTop = new LinearLayout(this);
        modeTop.setOrientation(LinearLayout.HORIZONTAL);
        modeTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeDot = text("●", 14, Typeface.BOLD, GREEN);
        TextView modeTitle = text("  Sudden Death Mode", 15, Typeface.BOLD, TEXT);
        modeTop.addView(modeDot);
        modeTop.addView(modeTitle);
        mode.addView(modeTop, matchWrap());
        TextView modeCopy = text("Built for five-second auctions. First plausible read starts pricing immediately while verification continues.",
                12.5f, Typeface.NORMAL, SECONDARY);
        modeCopy.setLineSpacing(0, 1.08f);
        modeCopy.setPadding(0, dp(8), 0, 0);
        mode.addView(modeCopy, matchWrap());
        page.addView(mode, margins(dp(18), 0));

        TextView primary = actionButton("Start Live Scan", BLUE, Color.rgb(8, 15, 24));
        primary.setOnClickListener(v -> startCaptureFlow());
        page.addView(primary, margins(dp(16), 0));

        TextView primaryHint = text("Share only Whatnot when Android asks what to capture.",
                11.5f, Typeface.NORMAL, MUTED);
        primaryHint.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(primaryHint, margins(dp(8), dp(16)));

        LinearLayout permissionCard = card(SURFACE, BORDER, 16);
        permissionCard.setPadding(dp(16), dp(15), dp(16), dp(15));

        TextView permissionHeader = text("Floating overlay", 15, Typeface.BOLD, TEXT);
        permissionCard.addView(permissionHeader, matchWrap());

        LinearLayout permissionRow = new LinearLayout(this);
        permissionRow.setOrientation(LinearLayout.HORIZONTAL);
        permissionRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionRow.setPadding(0, dp(9), 0, dp(12));
        overlayDot = text("●", 13, Typeface.BOLD, YELLOW);
        overlayStatus = text(" Permission required", 13, Typeface.BOLD, SECONDARY);
        permissionRow.addView(overlayDot);
        permissionRow.addView(overlayStatus);
        permissionCard.addView(permissionRow, matchWrap());

        TextView allowOverlay = secondaryButton("Enable floating overlay");
        allowOverlay.setOnClickListener(v -> openOverlaySettings());
        permissionCard.addView(allowOverlay, matchWrap());
        page.addView(permissionCard, margins(0, 0));

        LinearLayout privacyCard = card(SURFACE, BORDER, 16);
        privacyCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        TextView privacyTitle = text("Private by design", 14, Typeface.BOLD, TEXT);
        TextView privacyCopy = text("Card frames stay on your phone for on-device OCR. CardLens sends only the parsed card lookup to the pricing source.",
                12, Typeface.NORMAL, SECONDARY);
        privacyCopy.setLineSpacing(0, 1.08f);
        privacyCopy.setPadding(0, dp(7), 0, 0);
        privacyCard.addView(privacyTitle);
        privacyCard.addView(privacyCopy);
        page.addView(privacyCard, margins(dp(12), 0));

        TextView stop = text("Stop active scan", 13, Typeface.BOLD, SECONDARY);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(dp(12), dp(15), dp(12), dp(15));
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(intent);
            Toast.makeText(this, "CardLens stopped", Toast.LENGTH_SHORT).show();
        });
        page.addView(stop, margins(dp(8), 0));

        return scroll;
    }

    private void updatePermissionStatus() {
        if (overlayStatus == null || overlayDot == null) return;
        boolean ready = Settings.canDrawOverlays(this);
        overlayStatus.setText(ready ? " Ready" : " Permission required");
        overlayStatus.setTextColor(ready ? GREEN : SECONDARY);
        overlayDot.setTextColor(ready ? GREEN : YELLOW);
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable the floating overlay first", Toast.LENGTH_LONG).show();
            openOverlaySettings();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing was not granted", Toast.LENGTH_SHORT).show();
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private LinearLayout card(int fill, int stroke, int radiusDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(rounded(fill, stroke, radiusDp));
        return layout;
    }

    private TextView actionButton(String label, int fill, int textColor) {
        TextView view = text(label, 16, Typeface.BOLD, textColor);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(58));
        view.setPadding(dp(18), dp(16), dp(18), dp(16));
        view.setBackground(rounded(fill, fill, 14));
        view.setClickable(true);
        return view;
    }

    private TextView secondaryButton(String label) {
        TextView view = text(label, 14, Typeface.BOLD, TEXT);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(SURFACE_RAISED, BORDER, 12));
        view.setClickable(true);
        return view;
    }

    private TextView pill(String label, int fill, int textColor) {
        TextView view = text(label, 11, Typeface.BOLD, textColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setBackground(rounded(fill, BORDER, 999));
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private TextView text(String value, float sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
