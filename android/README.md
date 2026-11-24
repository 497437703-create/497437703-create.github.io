# AI 会记 — Android M1 (MC G1)

Native Android app for the Lenovo MC G1 smart board (PTB1302, Android 14, 4K touch).
Kotlin + Jetpack Compose, landscape-only, sized for 65–98″ screens (88 dp touch targets,
32 dp edge guard, palm rejection).

## Build

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or export ANDROID_HOME
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:assembleRelease        # R8-minified; signed with the debug key until a release keystore exists
```

Install on the board (developer mode / USB debugging):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first launch shows the permission guide (S22). Out of the box both server profiles use the
built-in **demo backend**: a scripted 5-language project meeting with captions, translation,
speakers and minutes. No server is needed for a walkthrough.

## M1 scope (product spec §8.1)

| Area | Where |
|---|---|
| In-room meeting, full-screen captions (S04) | `ui/meeting/LiveMeetingScreen.kt` |
| Video meeting beside Tencent Meeting: split screen by default (`FLAG_ACTIVITY_LAUNCH_ADJACENT`), floating panel when the split isn't possible (S05/S06) | `vc/VcLauncher.kt`, `service/FloatingPanelService.kt` |
| USB omni-mic capture, AEC/NS, far-end capture via MediaProjection playback capture | `audio/AudioCaptureEngine.kt`, `audio/AudioDevices.kt` |
| Pre-meeting sheet, self-check, capture consent (S03/S16b) | `ui/home/StartFlow.kt` |
| Speaker labels, speed notes (mark/excerpt/tags), notes drawer | `ui/meeting/Captions.kt`, `ui/meeting/LiveSheets.kt` |
| Offline spool and backfill, link banner | `core/provider/remote/FrameSpool.kt` |
| Minutes with 7 templates, each item traceable to its transcript lines (S08/S10/S19) | `ui/summary/*`, `core/provider/minutes/` |
| Share link + QR, deep links `aihuiji://summary/{id}`, `aihuiji://meeting/{code}` | `ui/meeting/LiveSheets.kt`, `MainActivity.kt` |
| Export PDF / Markdown / TXT | `export/Exporter.kt` |
| Settings behind a 4–6 digit admin PIN; server profiles, tokens in Android Keystore | `ui/settings/SettingsScreen.kt`, `data/TokenVault.kt` |
| 180-day retention, 20 meetings cached on the device, crash recovery | `data/MeetingRepository.kt`, `ui/home/HomeScreen.kt` |
| Languages zh/en/fr/de/ja | `core/model/Models.kt` (`Lang`) |

## Providers

All AI work goes through `core/provider/Providers.kt`: `AsrProvider`, `TranslationProvider`,
`SpeakerProvider`, `MinutesProvider` and `ServerGateway`, bundled per `ServerProfile`
(SCH-900S enterprise server or Lenovo cloud). `ProviderFactory` picks:

- **Demo** (`core/provider/demo/`) when the profile's demo switch is on or no URL is set.
- **Remote** (`core/provider/remote/`) otherwise. The REST/WebSocket contract in
  `RemoteContract.kt` is **provisional**. The real SCH-900S and Lenovo cloud APIs are not
  published yet, so only the DTOs and `RemoteProviders.kt` should need to change once they are.

Cloud fallback is off by default. Plain `http://` is allowed for on-premises appliances and
Settings flags it as unencrypted.

## Items to verify on a real G1

- Playback capture of VoIP audio (`USAGE_VOICE_COMMUNICATION`) needs
  `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, which only a privileged or preinstalled build gets.
  A sideloaded APK captures only media, game and unknown usages, so the remote channel can stay
  silent if Tencent Meeting plays call audio as voice communication or opts out of capture.
  Platform AEC then also removes the far end from the mic, so remote speech would be missing.
  The panel shows "仅记录本地声音" only when the user declines capture or capture fails to start,
  not in this silent case. This is the top on-device check.
- Whether the G1 launcher honours `FLAG_ACTIVITY_LAUNCH_ADJACENT` for Tencent Meeting. If it
  doesn't, the app falls back to the floating panel automatically.
- Name and AEC behaviour of the USB omni mic, through `AudioDevices.bestMic`.
