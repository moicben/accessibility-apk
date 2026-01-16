package com.andtracker;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal signaling client (REST) for WebRTC offer/answer/ICE exchange.
 * Base URL should point to the Next.js app, e.g. https://your-domain.com
 */
public final class SignalingClient {
    private static final String TAG = "SignalingClient";

    private final String baseUrl;

    public SignalingClient(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public JSONObject createSession(int screenWidth, int screenHeight) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("screenWidth", screenWidth);
        payload.put("screenHeight", screenHeight);
        return postJson("/api/webrtc/session", payload, null);
    }

    public void publishOffer(String sessionId, String secret, JSONObject offer) throws Exception {
        postJson("/api/webrtc/session/" + sessionId + "/offer", offer, secret);
    }

    public JSONObject fetchAnswer(String sessionId, String secret) throws Exception {
        return getJson("/api/webrtc/session/" + sessionId + "/answer", secret);
    }

    public void publishIce(String sessionId, String secret, JSONObject candidate) throws Exception {
        postJson("/api/webrtc/session/" + sessionId + "/ice/phone", candidate, secret);
    }

    public JSONArray fetchRemoteIce(String sessionId, String secret) throws Exception {
        JSONObject res = getJson("/api/webrtc/session/" + sessionId + "/ice/web", secret);
        if (res == null) return new JSONArray();
        return res.optJSONArray("candidates") != null ? res.optJSONArray("candidates") : new JSONArray();
    }

    private JSONObject postJson(String path, JSONObject payload, String secret) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (secret != null && !secret.isEmpty()) {
                connection.setRequestProperty("X-Session-Secret", secret);
            }
            connection.setDoOutput(true);

            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(input);
            }

            int code = connection.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + " body=" + body);
            }
            return body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject getJson(String path, String secret) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            if (secret != null && !secret.isEmpty()) {
                connection.setRequestProperty("X-Session-Secret", secret);
            }

            int code = connection.getResponseCode();
            if (code == 204) return null;
            InputStream stream = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + " body=" + body);
            }
            return body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            Log.e(TAG, "GET failed path=" + path, e);
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}

