# DALE

DALE is an Android app-locker that protects paired apps with a dedicated lock screen. It uses an accessibility service to detect app switches, shows an overlay lock screen when a protected app is opened, and keeps activity/usage logs for each protected app group.

## Features

### Paired app groups
Features independent credentials (PIN or pattern) per app group, allowing customized security for your paired applications.

### Overlay lock screen
Displays a secure overlay lock screen when opening protected apps, complete with optional biometric unlock.

### Crossover unlock
Seamlessly switch between paired apps without repeatedly unlocking, thanks to a one-time transition token.

### Uninstall protection
Provides uninstall protection for your protected apps to prevent unauthorized deletion.

### Activity & usage logs
Maintains thorough activity and usage logs for each protected app group with local storage and optional analytics support.

## Key permissions (AndroidManifest.xml)
- `POST_NOTIFICATIONS` – status and protection warnings.
- `RECEIVE_BOOT_COMPLETED` – restart monitoring after boot/update.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` – keep monitoring reliable.
- `USE_BIOMETRIC` and `VIBRATE` – biometric and haptic unlock.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` – background monitoring service.
- `INTERNET` – Firebase analytics/database.

## Requirements
- **Android Studio** (or Gradle CLI)
- **JDK 11**
- **minSdk 26**, **targetSdk 36**, **compileSdk 36**
- `app/google-services.json` (Firebase config)

## Setup & run
1. Open the project in Android Studio.
2. Ensure `app/google-services.json` is present.
3. Sync Gradle and run the `app` configuration on a device/emulator.
4. On first launch, complete setup and **enable the Accessibility Service** when prompted.

## Build & test
```bash
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

## Project structure
```
app/                     # Main Android app module
References/              # Reference code (material-components-android)
```
