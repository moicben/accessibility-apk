package com.andtracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory signaling store on the phone (single-process).
 * No auth, no persistence: simplest possible.
 */
public final class AndroidSignalingStore {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private AndroidSignalingStore() {}

    public static Session create(int screenWidth, int screenHeight) {
        String sessionId = randomId(12);
        Session s = new Session(sessionId, screenWidth, screenHeight);
        SESSIONS.put(sessionId, s);
        return s;
    }

    public static Session get(String sessionId) {
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    private static String randomId(int len) {
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int idx = (int) Math.floor(Math.random() * alphabet.length());
            sb.append(alphabet.charAt(idx));
        }
        return sb.toString();
    }

    public static final class Session {
        public final String sessionId;
        public final int screenWidth;
        public final int screenHeight;
        public volatile JSONObject offer;
        public volatile JSONObject answer;
        public final JSONArray icePhone = new JSONArray();
        public final JSONArray iceWeb = new JSONArray();

        private Session(String sessionId, int screenWidth, int screenHeight) {
            this.sessionId = sessionId;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
        }

        public synchronized void addIcePhone(JSONObject cand) {
            icePhone.put(cand);
        }

        public synchronized void addIceWeb(JSONObject cand) {
            iceWeb.put(cand);
        }

        public synchronized JSONArray copyIcePhone() {
            try {
                return new JSONArray(icePhone.toString());
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        public synchronized JSONArray copyIceWeb() {
            try {
                return new JSONArray(iceWeb.toString());
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }
}

