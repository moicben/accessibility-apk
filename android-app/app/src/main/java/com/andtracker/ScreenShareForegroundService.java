package com.andtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.Nullable;

public class ScreenShareForegroundService extends Service {
    public static final String ACTION_START = "com.andtracker.action.SCREENSHARE_START";
    public static final String ACTION_STOP = "com.andtracker.action.SCREENSHARE_STOP";

    public static final String EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData";

    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "screenshare";

    private WebRtcStreamer streamer;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopStreaming();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            Intent projectionData = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA);
            if (projectionData == null) {
                ScreenShareState.setFatalError("MediaProjection data manquante");
                stopSelf();
                return START_NOT_STICKY;
            }

            ensureNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.screenshare_notification_text)));
            startStreaming(projectionData);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startStreaming(Intent projectionData) {
        if (streamer != null) return;

        // Start embedded signaling server on the phone (simplest).
        AndroidSignalingServer.startIfNeeded(AndroidSignalingServer.DEFAULT_PORT);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.widthPixels = 720;
            metrics.heightPixels = 1280;
        }

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        streamer = new WebRtcStreamer(
                getApplicationContext(),
                "http://127.0.0.1:" + AndroidSignalingServer.DEFAULT_PORT,
                projectionData,
                width,
                height,
                new WebRtcStreamer.Events() {
                    @Override
                    public void onSessionCreated(String sessionId, String secret) {
                        ScreenShareState.setSession(sessionId, secret);
                        ScreenShareState.setStatus("Session prête");
                        updateNotification("Session: " + sessionId);
                    }

                    @Override
                    public void onStatus(String status) {
                        ScreenShareState.setStatus(status);
                        updateNotification(status);
                    }

                    @Override
                    public void onFatalError(String error) {
                        ScreenShareState.setFatalError(error);
                        updateNotification("Erreur: " + error);
                        stopSelf();
                    }
                }
        );
        streamer.start();
    }

    private void stopStreaming() {
        if (streamer != null) {
            streamer.stop();
            streamer = null;
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screenshare_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.screenshare_channel_desc));
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setLightColor(Color.BLUE);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, ScreenShareActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent, flags);

        Intent stopIntent = new Intent(this, ScreenShareForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(getString(R.string.screenshare_notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopPending
                ).build())
                .setOngoing(true);

        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        // Keep signaling server running while service is alive; stop when service stops.
        AndroidSignalingServer.stopIfRunning();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

