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

Launching the app draws nothing full screen: whatever was on the display stays visible and the
floating caption window appears on top of it (no permission guide, self-check or wizard on launch).
If "display over other apps" is not yet allowed, Android's own settings page for it opens once;
the window shows as soon as it is granted. Microphone and screen-audio capture are asked with
Android's system dialogs only when a meeting is started. Records, minutes and settings open the
full app only when tapped from the floating window.

Out of the box both server profiles use the
built-in **demo backend**: a scripted 5-language project meeting with captions, translation,
speakers and minutes. No server is needed for a walkthrough.

## M1 scope (product spec §8.1)

| Area | Where |
|---|---|
| Launch goes straight to the floating window (idle state with one start button + 视频会议 switch); invisible entry activity handles overlay/mic/capture system prompts | `LauncherActivity.kt`, `ui/meeting/IdleBar.kt`, `service/CaptionBarService.kt` |
| Caption bar: docked at the bottom of the display (default 1660x400 dp), resizable from every edge/corner, bounds remembered; toolbar with pause / mark / notes / language / font / hide-original / more / minimize / end | `service/CaptionBarService.kt`, `ui/meeting/CaptionBar.kt` |
| Minimize collapses the bar into an animated floating icon (brand logo, level-reactive ticks, timer); tap to restore | `ui/meeting/MinimizedIcon.kt` |
| Video meeting: the conferencing app opens full screen and the caption bar floats above it (the floating window is the primary UI and needs the "display over other apps" permission) | `vc/VcLauncher.kt`, `service/CaptionBarService.kt` |
| USB omni-mic capture, AEC/NS, far-end capture via MediaProjection playback capture | `audio/AudioCaptureEngine.kt`, `audio/AudioDevices.kt` |
| Device self-check (on demand from the records screen only), capture consent via Android's dialog | `ui/home/StartFlow.kt`, `LauncherActivity.kt` |
| Speaker labels, speed notes (mark/excerpt/tags), notes drawer | `ui/meeting/Captions.kt`, `ui/meeting/LiveSheets.kt` |
| Offline spool and backfill, link banner | `core/provider/remote/FrameSpool.kt` |
| Minutes with 7 templates, each item traceable to its transcript lines (S08/S10/S19) | `ui/summary/*`, `core/provider/minutes/` |
| Share link + QR, deep links `aihuiji://summary/{id}`, `aihuiji://meeting/{code}` | `ui/meeting/LiveSheets.kt`, `MainActivity.kt` |
| Export PDF / Markdown / TXT (unchanged) | `export/Exporter.kt` |
| After the minutes are generated a stand-alone, scrollable dialog opens (the floating window hides while it is open): save PDF+MD+TXT to a folder on the display (Download/AI会记 by default, or any folder via the system folder picker, remembered) or take them away by QR code; share link and export stay one tap away. Also reachable from the floating window and the 带走 button on the minutes screen | `TakeawayActivity.kt`, `ui/summary/PostMeetingSheet.kt`, `export/MinutesSaver.kt` |
| Settings behind a 4–6 digit admin PIN; AI backend choice (Hunyuan cloud or SCH-900S), credentials in Android Keystore | `ui/settings/SettingsScreen.kt`, `data/TokenVault.kt` |
| 180-day retention, 20 meetings cached on the device, crash recovery | `data/MeetingRepository.kt`, `ui/history/HistoryScreen.kt` |
| Languages zh/en/fr/de/ja | `core/model/Models.kt` (`Lang`) |

## AI backends: Tencent Hunyuan cloud or on-prem SCH-900S

Speech recognition, translation and minutes always use Tencent Hunyuan models. The user picks
**where** they run in 设置 › AI 服务（腾讯混元）(behind the admin PIN), by tapping 设为当前 on one of
two profiles. The choice applies from the next meeting; the idle floating window shows it
("就绪 · SCH-900S" / "就绪 · 腾讯混元云", plus "（演示）" while the demo switch is on). Nothing is
checked or blocked at launch.

| Profile | ASR | Translation | Minutes |
|---|---|---|---|
| 企业私有化 · SCH-900S (Lenovo SCH-900S + Intel Arc B70) | AI 会记 gateway WebSocket on the appliance (`/v1/asr/stream`), speaker resolve, share links | Hunyuan-MT via the appliance's OpenAI-compatible endpoint | Hunyuan-A13B-Instruct via the same endpoint |
| 腾讯混元云 | Tencent Cloud real-time ASR (`wss://asr.cloud.tencent.com/asr/v2/<AppId>`, HMAC-SHA1 signed) | `hunyuan-translation` on `api.hunyuan.cloud.tencent.com/v1` | `hunyuan-turbos-latest` on the same API |

The meeting flow does not know which one is used. `ProviderFactory` turns the active
`ServerProfile` into a `ProviderBundle` (`AsrProvider`, `TranslationProvider`, `SpeakerProvider`,
`MinutesProvider`, `ServerGateway`, all in `core/provider/Providers.kt`); captions, translation,
minutes, takeaway/QR and saving only talk to those interfaces.

- `core/provider/hunyuan/HunyuanBackend.kt` builds both bundles.
- `HunyuanChatClient.kt`: OpenAI-compatible `/chat/completions` client (cloud and on-prem).
- `HunyuanPrompts.kt`: Hunyuan-MT translation prompt; minutes prompt and parser. The model gets
  numbered transcript lines and returns JSON whose `refs` are mapped back to `SourceRef`s, so every
  minutes item stays traceable to the transcript.
- `TencentAsr.kt`: Tencent real-time ASR signer, one WebSocket per audio channel, offline spool
  and backfill.
- `remote/RemoteProviders.kt`: the SCH-900S gateway (health, ASR WebSocket, speakers, share).
  This contract (`RemoteContract.kt`) is still **provisional** until the appliance API is
  published.
- `demo/`: scripted backend. It is used whenever a profile's 演示服务 switch is on (the default),
  so walkthroughs, CI and unit tests need no server or keys.

### Configure on-prem SCH-900S

| Field | Example | Default when empty |
|---|---|---|
| 服务地址（含端口） | `https://sch900s.corp.local:8443` | required |
| 语音识别 WebSocket 路径 | `/v1/asr/stream` | `/v1/asr/stream` |
| 混元模型接口（OpenAI 兼容） | `http://sch900s.corp.local:8000/v1` (vLLM / TGI serving Hunyuan) | `<服务地址>/v1` |
| 访问令牌 | sent as `Authorization: Bearer …` to the gateway and the model endpoint | none |
| 翻译模型 / 纪要模型 | `Hunyuan-MT-7B` / `Hunyuan-A13B-Instruct` | as shown |
| 超时 | 10 / 20 / 30 / 60 s (minutes generation gets 6× this) | 20 s |

Then switch 演示服务 off and tap 测试连接: it checks gateway health and pings the model endpoint.

### Configure Tencent Hunyuan cloud

| Field | Where to get it | Stored in |
|---|---|---|
| AppId | Tencent Cloud console › 账号信息 | settings |
| SecretId / SecretKey | 访问管理 › API 密钥 (needs ASR permission) | Android Keystore vault |
| 混元 API Key | 混元大模型 › API Key 管理 (OpenAI-compatible endpoint) | Android Keystore vault |
| API 地址 | — | `https://api.hunyuan.cloud.tencent.com/v1` |
| ASR 引擎 | `16k_zh_large`, `16k_zh`, … (other languages use `16k_<lang>`, auto-detect uses `16k_zh_en`) | `16k_zh_large` |
| 翻译模型 / 纪要模型 | — | `hunyuan-translation` / `hunyuan-turbos-latest` |
| 分享服务地址（可选） | an AI 会记 share service for short links | empty: the QR carries the on-device link |

Secrets are typed into password fields, written to `TokenVault` (AES-GCM key in Android
Keystore) on Done, and never shown again or stored in settings. No key ships in the APK.
Settings saved by older builds with a `LENOVO_CLOUD` profile load as the Hunyuan cloud profile.

### Errors

Every backend reports failures as `ProviderException` (`core/provider/ProviderError.kt`):
`NOT_CONFIGURED`, `UNREACHABLE`, `AUTH`, `QUOTA`, `TIMEOUT`, `SERVER`, `BAD_RESPONSE`.
`ProviderErrorText` turns them into one Chinese sentence naming the backend, the service and
the field to fix, for example "无法连接 SCH-900S（…）：请检查服务地址、端口和企业网络" or
"腾讯云凭证无效，请检查 AppId / SecretId / SecretKey / 混元 API Key". They show in the caption bar
(red banner above the captions), the minutes failure screen, 测试连接 and the device check.
Credential, quota and missing-configuration errors stop reconnect attempts; network drops keep
spooling audio and backfill once the link is back.

### Try it without hardware or keys

`tools/mock_hunyuan_server.py` (Python 3, standard library only) stands in for an SCH-900S:
gateway health, scripted streaming ASR, an OpenAI-compatible Hunyuan endpoint for translation and
minutes, speakers and share links. A wrong token returns 401.

```bash
python3 tools/mock_hunyuan_server.py --port 8090 --token demo-token
# Emulator: 服务地址 http://10.0.2.2:8090, 访问令牌 demo-token, 演示服务 off, 测试连接
```

`HunyuanBackendTest` covers both backends against MockWebServer (prompt and model, Bearer
header, 401 → AUTH, closed port → UNREACHABLE, slow server → TIMEOUT, Tencent ASR signing,
streaming and 4002 rejection, minutes JSON → traceable minutes).

### Limits

- Hunyuan cloud ASR has no voiceprint service: speakers are the stream's own labels per channel
  (local mic / far end); names can still be set by hand.
- Very long meetings are trimmed in the middle to about 60k characters before summarising; pick a
  long-context model for meetings over about 3 hours.
- 允许回退到腾讯混元云 (off by default) only acts when a meeting starts: if the selected SCH-900S
  does not answer its health check within 3 s and the cloud profile is configured with its demo
  switch off, that meeting runs on the cloud and the caption bar says so. It does not move a
  running meeting.

Plain `http://` is allowed for on-premises appliances and Settings flags it as unencrypted.

## Items to verify on a real G1

- Playback capture of VoIP audio (`USAGE_VOICE_COMMUNICATION`) needs
  `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, which only a privileged or preinstalled build gets.
  A sideloaded APK captures only media, game and unknown usages, so the remote channel can stay
  silent if Tencent Meeting plays call audio as voice communication or opts out of capture.
  Platform AEC then also removes the far end from the mic, so remote speech would be missing.
  The panel shows "仅记录本地声音" only when the user declines capture or capture fails to start,
  not in this silent case. This is the top on-device check.
- Whether the G1 grants `SYSTEM_ALERT_WINDOW` to a sideloaded build (Settings > Apps > AI 会记 >
  Display over other apps) and whether its palm/touch filter lets the caption bar edges be dragged.
  Without the permission the caption bar is shown inside the app only.
- Name and AEC behaviour of the USB omni mic, through `AudioDevices.bestMic`.
