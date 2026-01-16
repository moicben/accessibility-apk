package com.andtracker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private TextView statusTextView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Interface minimale
        setContentView(createSimpleLayout());
        
        // Vérifier si le service d'accessibilité est activé
        checkAccessibilityService();
    }
    
    private android.view.View createSimpleLayout() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setGravity(android.view.Gravity.CENTER);
        
        TextView title = new TextView(this);
        title.setText("Key Tracker");
        title.setTextSize(24);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        layout.addView(title);
        
        statusTextView = new TextView(this);
        statusTextView.setTextSize(16);
        statusTextView.setGravity(android.view.Gravity.CENTER);
        statusTextView.setPadding(0, 0, 0, 30);
        layout.addView(statusTextView);
        
        Button settingsButton = new Button(this);
        settingsButton.setText("Ouvrir les paramètres d'accessibilité");
        settingsButton.setOnClickListener(v -> openAccessibilitySettings());
        layout.addView(settingsButton);
        
        return layout;
    }
    
    private void checkAccessibilityService() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices = 
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        
        boolean isServiceEnabled = false;
        String serviceName = getPackageName() + "/" + KeyTrackerService.class.getName();
        
        for (android.accessibilityservice.AccessibilityServiceInfo service : enabledServices) {
            String enabledService = service.getResolveInfo().serviceInfo.packageName + "/" + 
                                   service.getResolveInfo().serviceInfo.name;
            if (serviceName.equals(enabledService)) {
                isServiceEnabled = true;
                break;
            }
        }
        
        if (statusTextView != null) {
            if (isServiceEnabled) {
                statusTextView.setText("Service activé ✓");
                statusTextView.setTextColor(0xFF4CAF50);
            } else {
                statusTextView.setText("Service non activé. Veuillez l'activer dans les paramètres.");
                statusTextView.setTextColor(0xFFF44336);
            }
        }
        
        if (!isServiceEnabled) {
            Toast.makeText(this, "Veuillez activer le service d'accessibilité", Toast.LENGTH_LONG).show();
        }
    }
    
    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Vérifier à nouveau quand l'utilisateur revient des paramètres
        checkAccessibilityService();
    }
}
