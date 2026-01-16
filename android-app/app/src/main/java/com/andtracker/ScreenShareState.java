package com.andtracker;

/**
 * Tiny in-memory state holder so the Activity can display pairing info and status.
 * (Best-effort, not persisted.)
 */
public final class ScreenShareState {
    private static volatile String sessionId;
    private static volatile String secret;
    private static volatile String status;
    private static volatile String fatalError;

    private ScreenShareState() {}

    public static void setSession(String id, String sec) {
        sessionId = id;
        secret = sec;
    }

    public static void setStatus(String s) {
        status = s;
    }

    public static void setFatalError(String e) {
        fatalError = e;
    }

    public static String getSessionId() { return sessionId; }
    public static String getSecret() { return secret; }
    public static String getStatus() { return status; }
    public static String getFatalError() { return fatalError; }
}

