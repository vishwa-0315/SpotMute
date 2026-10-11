# SpotMute (Android, React Native + Kotlin)

Mutes the phone's media volume while Spotify seems to be playing an ad, then restores the volume you had before.

**Everything lives in `D:\SpotMute`.** The project is created there, and this overlay folder ends up inside it as `D:\SpotMute\spotmute-overlay`.

## Read this first: what it can and cannot do

Spotify gives other apps **no "this is an ad" signal**. SpotMute only guesses, from what Spotify publishes in its media session (title, artist, album, media id, duration, which buttons are enabled) and in its notification text.

- It **can** detect ads that show up as "Advertisement" / artist "Spotify" (the commonly reported pattern) and ads whose media id looks like an ad URI.
- It **cannot** guarantee detection. A new Spotify version, another region/language, or an ad that carries normal-looking metadata will slip through.
- It **can** also mute a real track by mistake, for example a song literally titled "Spotify". The threshold is set conservatively (score 3) to reduce that, which means it prefers missing an ad over muting music.
- The first moment of an ad may be audible before Android delivers the update, and a small part of the next track may be muted.
- It changes the **whole phone's** music volume, so other apps playing audio are muted too during an ad.
- The in-app **"What Spotify is reporting"** panel and **Event log** exist so you can see what Spotify sends on your phone and tune detection (Extra ad titles).
- I could not run this on a real phone or compile it in my environment. Treat first build as something you may need to fix; the Troubleshooting section lists the likely spots.
- Spotify's terms may not like ad blocking, even local muting. Use it at your own discretion.

## How it works

| Piece | File | Job |
|---|---|---|
| Listener service | `SpotMuteListenerService.kt` | Android starts it once you grant Notification access. Watches Spotify's media session and notification, decides ad / not ad, mutes and restores. |
| Detector | `AdDetector.kt` | Scores the evidence (below). Pure Kotlin, no Android types. |
| Volume | `VolumeController.kt` | Saves the original music volume, lowers it, restores it exactly. |
| Settings | `Prefs.kt` | On/off, ad volume %, extra ad titles, and the saved volume (written immediately, so a crash cannot lose it). |
| Bridge | `SpotMuteModule.kt`, `SpotMutePackage.kt`, `SpotMuteBus.kt` | Lets the React Native screen read status/log and change settings. |
| Screen | `App.tsx` | Permission help, switch, live status, debug panel, event log, panic button. |

Detection score (an ad needs **3 or more**):

| Evidence | Points |
|---|---|
| Metadata title is exactly an ad word ("Advertisement" etc.) | 3 |
| Media id starts with `spotify:ad` or contains `:ad:` | 3 |
| Metadata title is "Spotify" | 2 |
| Metadata artist is "Spotify" | 2 |
| Notification title/text is an ad word | 2 |
| Skip-next is not offered | 1 (also happens when free-tier skips run out) |
| No album and duration 5-45 s | 1 |

The ad words other than "advertisement" are my unverified translations. Add what your phone really shows in **Extra ad titles**.

Volume restoration: on the first ad event the current music volume index is saved, then the volume is set to 0 (or 10/20% if you choose). 300 ms after the ad stops being detected, the saved index is restored. If you changed the volume yourself during the ad, it is **not** overwritten. If the service is killed mid-ad, the saved value is still on disk and is restored the next time the service runs; the **Restore my volume now** button also does it manually.

## 1. Install the tools on Windows 11

1. **Node.js 22.11 or newer** (LTS) from nodejs.org. Check: `node -v`.
2. **JDK 17** (for example Microsoft Build of OpenJDK 17). The React Native docs recommend 17 and warn newer JDKs can cause problems. Check: `java -version`.
3. **Android Studio**. In Setup choose Android SDK, Android SDK Platform and Android Virtual Device. Then in *Settings > Languages & Frameworks > Android SDK*:
   - SDK Platforms: **Android 15 (API 35)**
   - SDK Tools: **Android SDK Build-Tools 36.0.0** and **Android SDK Command-line Tools (latest)**
4. **Environment variables** (Start > "Edit environment variables for your account"):
   - New user variable `ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`
   - Add to `Path`: `%LOCALAPPDATA%\Android\Sdk\platform-tools`
   - Open a **new** PowerShell afterwards. Check: `adb version`.
5. **VS Code** with extensions: *React Native Tools*, *ESLint*, and optionally *Kotlin* (fwcd).
6. **Phone**: Settings > About phone > tap Build number 7 times > Developer options > enable **USB debugging**.
   Check: connect via USB, accept the prompt, then `adb devices` should list it.

## 2. Create the project in `D:\SpotMute`

```powershell
D:
cd \
npx @react-native-community/cli@latest init SpotMute
```

This creates `D:\SpotMute`. (The folder must not exist yet, so do this **before** copying anything into it.)

## 3. Add the SpotMute files

1. Extract the zip so the folder `spotmute-overlay` sits inside the project: `D:\SpotMute\spotmute-overlay`.
2. Run the script:

```powershell
cd D:\SpotMute
powershell -ExecutionPolicy Bypass -File .\spotmute-overlay\apply-overlay.ps1 -ProjectPath D:\SpotMute
code .
```

The script copies the 7 Kotlin files next to `MainApplication.kt`, replaces `App.tsx`, adds the service to `AndroidManifest.xml`, and registers `SpotMutePackage()` in `MainApplication.kt`. It is safe to run twice.

### Manual steps (only if the script warns)

- `android\app\src\main\AndroidManifest.xml`, inside `<application>`:

```xml
<service
    android:name="com.spotmute.SpotMuteListenerService"
    android:label="SpotMute"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

- `MainApplication.kt`: add `add(SpotMutePackage())` inside the `PackageList(this).packages.apply { ... }` block.
- If your package is not `com.spotmute`, change the first line of each copied `.kt` file and the manifest name to match.

## 4. Build the APK

```powershell
cd D:\SpotMute\android
.\gradlew assembleRelease
```

The first build downloads a lot and can take 10+ minutes. Output:

`D:\SpotMute\android\app\build\outputs\apk\release\app-release.apk`

The React Native template signs release builds with its debug key, which is fine for installing on your own phone. For a properly signed build later, create a keystore with `keytool` and put it in `android\app\build.gradle` signing config.

## 5. Install on the phone

```powershell
adb install -r D:\SpotMute\android\app\build\outputs\apk\release\app-release.apk
```

Or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).

Why a release build and not the debug APK: a React Native **debug** build loads its JavaScript from the Metro dev server on your PC, so a debug APK copied to the phone usually shows a red "Unable to load script" screen. The **release** build bundles the JavaScript inside the APK, so it runs on its own.

An emulator is possible (`adb install` works the same), but you need an image with the Play Store and a Spotify login to get real ads, so a physical phone is the better test.

## 6. First run on the phone

1. Open **SpotMute**.
2. **Android 13+ only:** tap **Open app info**, then the **⋮** menu, then **Allow restricted settings**. Without this, Android greys out Notification access for sideloaded apps.
3. Tap **Notification access** and switch **SpotMute** on. Return to the app; the warning card should disappear. If it says "Service not connected", tap **Reconnect**.
4. Phone settings > Apps > SpotMute > Battery > **Unrestricted** (some brands, such as Xiaomi, Oppo, Vivo, Samsung, also have an "autostart" or "sleeping apps" list; add SpotMute there). Otherwise the system may stop the service.
5. Keep Spotify's own notifications allowed.
6. Start with the phone volume at a low level for the first tests, in case a detection restores the volume at a bad moment. Do not rely on this app for alerts or hearing protection.
7. Play Spotify on a free account and wait for an ad. Watch **What Spotify is reporting** and **Event log**.

## 7. Tuning when an ad is missed

1. During the ad, open SpotMute and note `title`, `artist`, `album` and the score.
2. If the title is an ad word in your language, type it into **Extra ad titles** and save.
3. If the score stays at 0-2 with ordinary-looking metadata, Spotify is not giving a usable signal on that ad, and this method cannot catch it. Do not expect 100%.
4. If music gets muted wrongly, check the signals listed in the log and tell me which signal fired, so the weights can be changed in `AdDetector.kt`.

## Troubleshooting

- **Build fails on a Kotlin/React Native API name** (for example `invalidate()` or `hasActiveReactInstance()` in `SpotMuteModule.kt`): React Native's native API changes between versions. Send me the exact Gradle error and I'll adjust that line.
- **App opens but says it needs the native module**: the package was not registered. Check `MainApplication.kt` contains `add(SpotMutePackage())`, then rebuild.
- **Notification access toggle is greyed out**: do step 6.2.
- **Nothing happens during ads**: check the Event log. "Spotify session not found" means Android is not exposing Spotify's session to the listener; confirm access is on and reconnect.
- **Volume stuck low**: tap **Restore my volume now**.
- **Uninstall/stop**: switch the toggle off, or revoke Notification access.

## File map

```
D:\SpotMute\
  README-SpotMute.md                  <- this guide (copy it here)
  App.tsx                             <- replaced by the overlay
  spotmute-overlay\                   <- the extracted zip
    apply-overlay.ps1
    App.tsx
    android-kotlin\*.kt
  android\app\src\main\java\com\spotmute\
    MainApplication.kt                <- patched
    Prefs.kt  VolumeController.kt  AdDetector.kt  SpotMuteBus.kt
    SpotMuteListenerService.kt  SpotMuteModule.kt  SpotMutePackage.kt
```
