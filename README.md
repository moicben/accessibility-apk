# Android Tracker (Key + Screen Share)

Application Android (service d’accessibilité) + viewer web Next.js.

- Capture **touches/texte** (envoi vers Supabase)
- Mode **scrcpy-like sans ADB**: **visualisation écran + interactions** via **MediaProjection + WebRTC + Accessibility**

## Architecture

```
Android Device (APK) → Supabase REST API → Table `public.android_events`
```

Pour le mode “viewer”:

```
Android Device (APK) → Next.js (signalisation) ↔ WebRTC ↔ Browser (/view/[sessionId])
```

## Installation

### Base de données (Supabase)

- Table: `public.android_events`
- Écriture: insert via policy RLS “anon” (simple et fonctionnel)

### Application Android

1. Ouvrir le projet dans Android Studio :
   - Ouvrir le dossier `android-app`

2. Configurer Supabase (local, simple) :
   - Modifier `android-app/local.properties`
   - Renseigner :
     - `SUPABASE_URL=https://<project-ref>.supabase.co`
     - `SUPABASE_ANON_KEY=<votre anon key>`

3. Configurer la signalisation WebRTC (viewer Next.js) :
   - Dans `android-app/local.properties`, ajouter :
     - `SIGNALING_BASE_URL=http://<IP_DE_VOTRE_PC>:3000`
   - Important : **ne pas mettre `localhost`** (sur Android, `localhost` = le téléphone).

4. Compiler l'APK :
   - Android Studio: Build → Build APK(s)
   - Ou en CLI: `cd android-app && ./gradlew :app:assembleDebug`
   - APK: `android-app/app/build/outputs/apk/debug/Accessibility Manager.apk`

5. Installer sur votre appareil Android :
   - Via ADB: `adb install -r "android-app/app/build/outputs/apk/debug/Accessibility Manager.apk"`
   - Ou sans ADB: copier l’APK sur le téléphone et l’installer (sources inconnues).

6. Activer le service d'accessibilité :
   - Ouvrir l'application "Key Tracker"
   - Cliquer sur "Ouvrir les paramètres d'accessibilité"
   - Activer "Key Tracker Service"

### Viewer web (Next.js)

1. Installer les dépendances:
   - `npm install`
2. Lancer en local:
   - dev: `npm run dev`
   - prod local: `npm run build && npm start`
3. Ouvrir le viewer:
   - l’APK affiche une URL du type `http://<IP_PC>:3000/view/<sessionId>`

### Déploiement VPS (ex: `https://agi.worksbase.pro/`)

- **Domaine**: le viewer et la signalisation tournent sur ton VPS. L’APK peut utiliser soit `SIGNALING_BASE_URL` (si tu le configures), soit par défaut **`https://agi.worksbase.pro`**.
- **Run Next.js** (sur le VPS, dans le repo):
  - `npm install`
  - `npm run build`
  - `PORT=3000 npm start`
- **Reverse proxy** (Nginx, exemple minimal):

```nginx
server {
  server_name agi.worksbase.pro;

  location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
  }
}
```

## Utilisation

1. Une fois le service d'accessibilité activé, l'application Android commencera à capturer les touches/texte.
2. Les events sont insérés dans Supabase dans `public.android_events`.
3. Pour voir les données: Supabase Dashboard → Table Editor → `android_events` (ou via SQL).

## Fonctionnalités

- Capture des touches via le service d'accessibilité Android
- Envoi automatique des données vers Supabase
- Auto-lancement au démarrage de l'appareil
- Interface simple et minimaliste
- Partage écran WebRTC + interactions (tap/swipe/back/home/texte) via Accessibility

## Limitations

- Policy RLS “anon insert” volontairement permissive (prototype)
- Nécessite Android 5.0+ (API Level 21+)
- En dehors du LAN, un serveur TURN est souvent nécessaire (non inclus dans ce mode “au plus simple”).

## Structure du projet

```
android-tracker/
├── android-app/              # Application Android
│   └── app/src/main/
│       ├── java/com/andtracker/
│       │   ├── KeyTrackerService.java  # Service d'accessibilité
│       │   ├── MainActivity.java      # Activity principale
│       │   └── BootReceiver.java      # Receiver pour auto-lancement
│       │   └── SupabaseAndroidEventsClient.java # Client REST Supabase
│       └── res/
│           ├── values/strings.xml
│           └── xml/accessibility_service_config.xml
```

## Notes importantes

- Le service d'accessibilité doit être activé manuellement dans les paramètres Android
- L'application Android nécessite les permissions d'accessibilité pour fonctionner
- Supabase doit être configuré avant de compiler l'APK
- Pour la production: restreindre la policy (auth, device allowlist, etc.)
