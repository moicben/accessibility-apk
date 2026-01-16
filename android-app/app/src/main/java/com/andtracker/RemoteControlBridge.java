package com.andtracker;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

/**
 * Bridge between network/control channel and AccessibilityService actions.
 */
public final class RemoteControlBridge {
    private static final String TAG = "RemoteControlBridge";

    private RemoteControlBridge() {}

    public static void handleControlMessage(Context context, JSONObject json) {
        if (json == null) return;
        KeyTrackerService svc = KeyTrackerService.getInstance();
        if (svc == null) {
            Log.w(TAG, "Accessibility service not active");
            return;
        }

        String type = json.optString("type", "");
        switch (type) {
            case "tap": {
                double x = json.optDouble("x", 0.5);
                double y = json.optDouble("y", 0.5);
                svc.performTap(x, y);
                break;
            }
            case "swipe": {
                double x1 = json.optDouble("x1", 0.5);
                double y1 = json.optDouble("y1", 0.5);
                double x2 = json.optDouble("x2", 0.5);
                double y2 = json.optDouble("y2", 0.5);
                long durationMs = json.optLong("durationMs", 250);
                svc.performSwipe(x1, y1, x2, y2, durationMs);
                break;
            }
            case "global": {
                String action = json.optString("action", "");
                svc.performGlobal(action);
                break;
            }
            case "text": {
                String text = json.optString("text", "");
                svc.performSetText(text);
                break;
            }
            default:
                Log.d(TAG, "Unknown control type=" + type + " payload=" + json);
        }
    }
}

