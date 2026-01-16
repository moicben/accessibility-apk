# Android Key Tracker

Application Next.js pour afficher en temps réel les touches tapées sur un appareil Android.

## Architecture

```
Android Device (APK) → API Endpoint → In-Memory Store → SSE Stream → Next.js Frontend
```

## Installation et déploiement

### Application Next.js

1. Installer les dépendances :
```bash
npm install
```

2. Lancer en développement :
```bash
npm run dev
```

3. Déployer sur Vercel :
```bash
npm install -g vercel
vercel
```

Après le déploiement, notez l'URL de votre application (ex: `https://votre-app.vercel.app`)

### Application Android

1. Ouvrir le projet dans Android Studio :
   - Ouvrir le dossier `android-app`

2. Configurer l'URL de l'endpoint :
   - Modifier `android-app/app/src/main/res/values/strings.xml`
   - Remplacer `https://votre-app.vercel.app/api/keys` par votre URL Vercel

3. Compiler l'APK :
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - L'APK sera généré dans `android-app/app/build/outputs/apk/`

4. Installer sur votre appareil Android :
   - Transférer l'APK sur votre appareil
   - Autoriser l'installation depuis des sources inconnues
   - Installer l'APK

5. Activer le service d'accessibilité :
   - Ouvrir l'application "Key Tracker"
   - Cliquer sur "Ouvrir les paramètres d'accessibilité"
   - Activer "Key Tracker Service"

## Utilisation

1. Une fois le service d'accessibilité activé, l'application Android commencera à capturer les touches
2. Les touches sont envoyées à l'endpoint API `/api/keys`
3. Ouvrir l'application web sur `https://votre-app.vercel.app`
4. Les touches apparaîtront en temps réel via Server-Sent Events (SSE)

## Fonctionnalités

- Capture des touches via le service d'accessibilité Android
- Envoi automatique des données vers l'API
- Affichage en temps réel sur la page web
- Auto-lancement au démarrage de l'appareil
- Interface simple et minimaliste

## Limitations

- Pas de persistance des données (stockage en mémoire uniquement)
- Les données sont perdues au redémarrage du serveur Vercel
- Pas d'authentification (endpoint public)
- Nécessite Android 5.0+ (API Level 21+)

## Structure du projet

```
android-tracker/
├── app/
│   ├── api/
│   │   ├── keys/
│   │   │   └── route.js      # Endpoint POST pour recevoir les touches
│   │   └── stream/
│   │       └── route.js      # Endpoint SSE pour diffuser les touches
│   ├── layout.jsx            # Layout principal
│   └── page.jsx              # Page principale avec affichage en temps réel
├── lib/
│   └── store.js              # Store en mémoire avec EventEmitter
├── android-app/              # Application Android
│   └── app/src/main/
│       ├── java/com/andtracker/
│       │   ├── KeyTrackerService.java  # Service d'accessibilité
│       │   ├── MainActivity.java      # Activity principale
│       │   └── BootReceiver.java      # Receiver pour auto-lancement
│       └── res/
│           ├── values/strings.xml
│           └── xml/accessibility_service_config.xml
├── package.json
├── next.config.js
└── vercel.json

```

## Notes importantes

- Le service d'accessibilité doit être activé manuellement dans les paramètres Android
- L'application Android nécessite les permissions d'accessibilité pour fonctionner
- L'URL de l'endpoint doit être configurée avant de compiler l'APK
- Pour la production, considérez ajouter une authentification pour sécuriser l'endpoint API
