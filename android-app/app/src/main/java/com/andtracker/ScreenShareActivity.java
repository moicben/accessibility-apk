package com.andtracker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.content.Context;
import android.net.wifi.WifiManager;

public class ScreenShareActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 2001;
    private static final int REQ_NOTIF = 2002;

    private TextView info;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());
        refreshLoop();
    }

    private android.view.View createLayout() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("Partage écran (WebRTC)");
        title.setTextSize(20);
        layout.addView(title);

        info = new TextView(this);
        info.setTextSize(14);
        info.setPadding(0, 20, 0, 20);
        layout.addView(info);

        Button start = new Button(this);
        start.setText("Démarrer");
        start.setOnClickListener(v -> startFlow());
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopFlow());
        layout.addView(stop);

        Button a11y = new Button(this);
        a11y.setText("Paramètres d'accessibilité");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(a11y);

        return layout;
    }

    private void startFlow() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
        }
        requestMediaProjection();
    }

    private void requestMediaProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            ScreenShareState.setFatalError("MediaProjectionManager indisponible");
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void stopFlow() {
        Intent stop = new Intent(this, ScreenShareForegroundService.class);
        stop.setAction(ScreenShareForegroundService.ACTION_STOP);
        startService(stop);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestMediaProjection();
            } else {
                ScreenShareState.setFatalError("Permission notifications refusée (requise pour le foreground service)");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                ScreenShareState.setFatalError("Autorisation capture écran refusée");
                return;
            }
            Intent start = new Intent(this, ScreenShareForegroundService.class);
            start.setAction(ScreenShareForegroundService.ACTION_START);
            start.putExtra(ScreenShareForegroundService.EXTRA_MEDIA_PROJECTION_DATA, data);
            startService(start);
        }
    }

    private void refreshLoop() {
        handler.postDelayed(() -> {
            String sid = ScreenShareState.getSessionId();
            String secret = ScreenShareState.getSecret();
            String status = ScreenShareState.getStatus();
            String err = ScreenShareState.getFatalError();

            StringBuilder sb = new StringBuilder();
            String ip = getLocalIpV4();
            sb.append("Signalisation (sur Android): http://")
                    .append(ip != null ? ip : "<IP_ANDROID>")
                    .append(":")
                    .append(AndroidSignalingServer.DEFAULT_PORT)
                    .append("\n\n");
            if (status != null) sb.append("Status: ").append(status).append("\n");
            if (sid != null && !sid.isEmpty()) sb.append("Session: ").append(sid).append("\n");
            if (secret != null && !secret.isEmpty()) sb.append("Secret: ").append(secret).append("\n");
            if (sid != null && !sid.isEmpty() && secret != null && !secret.isEmpty()) {
                sb.append("\nURL viewer: ").append(BuildConfig.SIGNALING_BASE_URL)
                        .append("/view/").append(sid).append("\n");
            } else if (sid != null && !sid.isEmpty()) {
                sb.append("\nURL viewer (PC): http://<IP_PC>:3000/view/").append(sid)
                        .append("?sig=http://")
                        .append(ip != null ? ip : "<IP_ANDROID>")
                        .append(":")
                        .append(AndroidSignalingServer.DEFAULT_PORT)
                        .append("\n");
            }
            if (err != null) sb.append("\nErreur: ").append(err).append("\n");
            info.setText(sb.toString());

            refreshLoop();
        }, 700);
    }

    private String getLocalIpV4() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) return null;
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

