package com.andtracker;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Petit écran "keep awake" best-effort.
 *
 * Objectif: garder l'écran allumé sans ADB, via:
 * - FLAG_KEEP_SCREEN_ON
 * - (optionnel) FLAG_TURN_SCREEN_ON / showWhenLocked selon options
 *
 * L'activité peut s'auto-fermer après un délai.
 */
public final class KeepAwakeActivity extends Activity {
    public static final String EXTRA_FINISH_AFTER_MS = "finishAfterMs";
    public static final String EXTRA_TURN_SCREEN_ON = "turnScreenOn";
    public static final String EXTRA_SHOW_WHEN_LOCKED = "showWhenLocked";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean turnScreenOn = getIntent() != null && getIntent().getBooleanExtra(EXTRA_TURN_SCREEN_ON, true);
        boolean showWhenLocked = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_WHEN_LOCKED, false);
        long finishAfterMs = getIntent() != null ? getIntent().getLongExtra(EXTRA_FINISH_AFTER_MS, 0) : 0;

        // Toujours garder l'écran allumé tant que l'activité est au foreground.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Best-effort: réveiller l'écran. (Certaines OEM/versions peuvent ignorer.)
        if (turnScreenOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }

        if (showWhenLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            }
        }

        TextView tv = new TextView(this);
        tv.setText("KeepAwakeActivity\n\nCet écran existe uniquement pour garder l’écran allumé (FLAG_KEEP_SCREEN_ON).\nTu peux le fermer.");
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16f);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        setContentView(tv);

        if (finishAfterMs > 0) {
            long d = Math.max(200, finishAfterMs);
            handler.postDelayed(this::finish, d);
        }
    }
}

