package com.andtracker;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SupabaseAndroidEventsClient {
    private static final String TAG = "SupabaseEvents";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private SupabaseAndroidEventsClient() {}

    public static void sendEvent(
            Context context,
            String packageName,
            String eventType,
            String eventValue
    ) {
        final String supabaseUrl = BuildConfig.SUPABASE_URL;
        final String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;

        if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
            Log.e(TAG, "Supabase non configuré. Ajoute SUPABASE_URL et SUPABASE_ANON_KEY dans android-app/local.properties");
            return;
        }

        final String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(supabaseUrl + "/rest/v1/android_events");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", supabaseAnonKey);
                connection.setRequestProperty("Authorization", "Bearer " + supabaseAnonKey);
                connection.setRequestProperty("Prefer", "return=minimal");
                connection.setDoOutput(true);

                JSONObject json = new JSONObject();
                if (deviceId != null) json.put("device_id", deviceId);
                if (packageName != null) json.put("package_name", packageName);
                if (eventType != null) json.put("event_type", eventType);
                if (eventValue != null) json.put("event_value", eventValue);

                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(input);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 201 || responseCode == 200) {
                    Log.d(TAG, "Event envoyé");
                } else {
                    Log.e(TAG, "Échec envoi event. Code=" + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur envoi event", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}

