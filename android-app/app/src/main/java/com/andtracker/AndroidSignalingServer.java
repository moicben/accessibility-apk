package com.andtracker;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Embedded HTTP server for WebRTC signaling, running on the phone.
 * Endpoints mimic the Next.js routes to keep both sides consistent:
 * - POST /api/webrtc/session
 * - GET/POST /api/webrtc/session/{id}/offer
 * - GET/POST /api/webrtc/session/{id}/answer
 * - GET/POST /api/webrtc/session/{id}/ice/phone
 * - GET/POST /api/webrtc/session/{id}/ice/web
 *
 * CORS: allow all (simplest).
 */
public final class AndroidSignalingServer extends NanoHTTPD {
    private static final String TAG = "AndroidSignalingServer";
    public static final int DEFAULT_PORT = 8765;

    private static volatile AndroidSignalingServer INSTANCE;

    public static synchronized void startIfNeeded(int port) {
        if (INSTANCE != null) return;
        try {
            AndroidSignalingServer s = new AndroidSignalingServer(port);
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            INSTANCE = s;
            Log.d(TAG, "Started on port " + port);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start", e);
        }
    }

    public static synchronized void stopIfRunning() {
        if (INSTANCE != null) {
            try { INSTANCE.stop(); } catch (Throwable ignored) {}
            INSTANCE = null;
        }
    }

    public AndroidSignalingServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            // Preflight
            if (Method.OPTIONS.equals(session.getMethod())) {
                return withCors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            }

            String uri = session.getUri() == null ? "" : session.getUri();
            Method method = session.getMethod();

            // POST /api/webrtc/session
            if ("/api/webrtc/session".equals(uri) && Method.POST.equals(method)) {
                JSONObject body = readJsonBody(session);
                int w = body.optInt("screenWidth", 0);
                int h = body.optInt("screenHeight", 0);
                AndroidSignalingStore.Session s = AndroidSignalingStore.create(w, h);
                JSONObject res = new JSONObject();
                res.put("sessionId", s.sessionId);
                res.put("secret", "");
                res.put("screenWidth", s.screenWidth);
                res.put("screenHeight", s.screenHeight);
                return withCors(json(res));
            }

            // Pattern: /api/webrtc/session/{sessionId}/...
            String prefix = "/api/webrtc/session/";
            if (uri.startsWith(prefix)) {
                String rest = uri.substring(prefix.length()); // {id}/offer ...
                String[] parts = rest.split("/");
                if (parts.length >= 2) {
                    String sessionId = parts[0];
                    AndroidSignalingStore.Session s = AndroidSignalingStore.get(sessionId);
                    if (s == null) return withCors(jsonError(404, "not_found"));

                    String kind = parts[1];

                    if ("offer".equals(kind)) {
                        if (Method.POST.equals(method)) {
                            s.offer = readJsonBody(session);
                            return withCors(jsonOk());
                        } else if (Method.GET.equals(method)) {
                            if (s.offer == null) return withCors(noContent());
                            return withCors(json(s.offer));
                        }
                    }

                    if ("answer".equals(kind)) {
                        if (Method.POST.equals(method)) {
                            s.answer = readJsonBody(session);
                            return withCors(jsonOk());
                        } else if (Method.GET.equals(method)) {
                            if (s.answer == null) return withCors(noContent());
                            return withCors(json(s.answer));
                        }
                    }

                    // ice/{side}
                    if ("ice".equals(kind) && parts.length >= 3) {
                        String side = parts[2]; // phone|web
                        if (Method.POST.equals(method)) {
                            JSONObject cand = readJsonBody(session);
                            if ("phone".equals(side)) s.addIcePhone(cand);
                            else if ("web".equals(side)) s.addIceWeb(cand);
                            else return withCors(jsonError(400, "bad_side"));
                            return withCors(jsonOk());
                        } else if (Method.GET.equals(method)) {
                            JSONArray cands = "phone".equals(side) ? s.copyIcePhone() : "web".equals(side) ? s.copyIceWeb() : null;
                            if (cands == null) return withCors(jsonError(400, "bad_side"));
                            JSONObject res = new JSONObject();
                            res.put("candidates", cands);
                            return withCors(json(res));
                        }
                    }
                }
            }

            return withCors(jsonError(404, "not_found"));
        } catch (Exception e) {
            Log.e(TAG, "serve error", e);
            return withCors(jsonError(500, "internal_error"));
        }
    }

    private static JSONObject readJsonBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        return body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static Response jsonOk() {
        try {
            return json(new JSONObject().put("ok", true));
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
        }
    }

    private static Response jsonError(int status, String code) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", code);
            return newFixedLengthResponse(Response.Status.lookup(status), "application/json", o.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.lookup(status), "application/json", "{\"error\":\"" + code + "\"}");
        }
    }

    private static Response json(JSONObject obj) {
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private static Response noContent() {
        return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "");
    }

    private static Response withCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
        r.addHeader("Access-Control-Max-Age", "86400");
        return r;
    }
}

