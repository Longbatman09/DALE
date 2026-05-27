# DALE

DALE is an Android app-locker that protects paired apps with a dedicated lock screen. It uses an accessibility service to detect app switches, shows an overlay lock screen when a protected app is opened, and keeps activity/usage logs for each protected app group.

![image alt](https://github.com/Longbatman09/DALE/blob/d67aa7a23ac0856f2fb61813261c7b8d05555dcc/Banner.png)

## Features

### Paired app groups
Features independent credentials (PIN or Pattern or Password) per app group, allowing customized security for your paired applications.

### Crossover unlock
Seamlessly switch between paired apps without repeatedly unlocking, thanks to a one-time transition token.
Displays a secure overlay lock screen when opening protected apps, complete with optional biometric unlock.

![image alt](https://github.com/Longbatman09/DALE/blob/d67aa7a23ac0856f2fb61813261c7b8d05555dcc/lockscreen.png)

### Uninstall protection
Provides uninstall protection for your protected apps to prevent unauthorized deletion.

![image alt](https://github.com/Longbatman09/DALE/blob/d67aa7a23ac0856f2fb61813261c7b8d05555dcc/uninstallprotection.png)

### Activity & usage logs
Maintains thorough activity and usage logs for each protected app group with local storage and optional analytics support.

## Structure and Flow 
Dale follows the structure as per the below image. 

![image alt](https://github.com/Longbatman09/DALE/blob/1c0c823bca10eb1dbf128c056bd9af6a7817357e/structure%20and%20flow.png)

## Feauture Plans
- Add Intruder camera capture
- Multi Dot pattern Support
- Personalize Lock Screen
- App Monitoring via Usage Polling
- More Stable Animations and UI

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
