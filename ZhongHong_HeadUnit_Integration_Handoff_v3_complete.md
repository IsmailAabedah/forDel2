# ZhongHong Android Head Unit — Integration Handoff (revision 3, complete control map) v2

> **Consolidated update: 30 October 2026.** This v2 is based on the latest available `ZhongHong_HeadUnit_Integration_Handoff_VERIFIED.md` plus the 28 October verification addendum, the 29–30 October CarCheck review, and the subsequent Bluetooth/GOC source analysis. The exact file named `ZhongHong_HeadUnit_Integration_Handoff_v2.md` was not present in the available file set, so the latest VERIFIED copy was used as the base rather than an older draft.
> **Read first:** Appendix H (Bluetooth music/steering-wheel path), Appendix I (corrected 32-bit odometer and maintenance calculation), and Appendix J (agreed launcher feature scope). These updates supersede conflicting older statements in the baseline.

Purpose: give another AI/developer everything needed to talk to **every unit of this car's head unit** from a normal Android app (a custom launcher / dashboard). It is built from (a) the factory APKs pulled from the unit, decompiled and read, and (b) on-device probe reports produced by a probe app running as a normal app.

> **Verification update (2026-10-28):** This handoff has been reconciled with `carcheck_20261027_224456.txt`. Read the updated Section 5 and Appendix G before implementation; they supersede older `[UNTESTED]` notes where the device probe now provides evidence. The full per-signal evidence and remaining gaps are in Appendix G.

## 0. Scope, legend, ground rules

**Confidence tags used everywhere**

| Tag | Meaning |
|---|---|
| `[CODE]` | Read directly in decompiled factory code (descriptors, transaction codes, constants, bit positions). High confidence on *format*. |
| `[DEVICE]` | Observed on the real unit in a probe report. |
| `[UNTESTED]` | Reachability or behaviour from a *third-party app* was never tried on the device. Treat as "should work, verify first". |
| `[NOT FOUND]` | Looked for it in the provided APKs and it is not there. |

**Ground rules**
- Target process: a normal app (uid `u0_aXX`, `untrusted_app`), `targetSdkVersion 22` (so all manifest permissions are granted at install; there is no runtime-permission UI to rely on).
- Out of scope: rooting, privilege escalation, modifying the system partition, or anything that needs system UID. Everything below uses interfaces that are exported/world-accessible or readable by any app.
- The third-party launcher `com.wicardtech.wicardcl` (QuickSmartDrive) was **deliberately not analysed or used**. Nothing here comes from it.
- Privacy: device serial number, device ID and GPS coordinates seen in the raw reports were removed.
- Language of the car: system locale `fa-IR`, time zone `Asia/Tehran`. The owner's UI language is Arabic (UI text is the app's own choice, independent of the system locale).

## 0.1 Project brief and agreed product scope (owner requirements; not all features are technically verified)

The target is a **standalone native Android launcher/dashboard APK** for the Saipa Quick Automatic Full Plus head unit. The app must run as an ordinary third-party app; do not require root, system UID, changes to `/system`, a paid API, or a modified firmware image. Kotlin is the preferred implementation language. Keep the code modular so each hardware/service integration can be tested independently.

### Agreed UI/UX requirements
- Arabic is the launcher's UI language; the head unit's system locale remains `fa-IR` and its timezone is `Asia/Tehran`. Do not assume that setting the UI language requires changing Android's system locale.
- Design for a fixed **952×600 px**, landscape, 7-inch display (~634×400 dp at density 1.5). Prioritise readable text, large touch targets, strong contrast and a low-clutter driving dashboard.
- Provide **multiple genuinely different dashboard layouts** (different composition, widget placement and hierarchy), not just several colour palettes. Colour themes may be an additional independent setting.
- Provide automatic day/night styling using the tested illumination callback where appropriate, plus a manual override.
- Optional startup greeting using the spoken Arabic greeting/name “إسماعيل”, controlled by a setting/toggle. Availability/quality of Arabic TTS on this Android image must be tested; allow a local recorded-voice fallback.
- Optional Arabic voice alerts for confirmed vehicle events (initial example: “الباب مفتوح”). Make alerts configurable, debounce them, avoid repeated announcements while a door remains open, and do not announce uncertain signals. TTS/voice playback itself is not yet tested.
- The final deliverable expected by the owner is an installable **APK**. Keep a reproducible source project/build for maintenance; do not make the runtime app depend on a cloud API.

### Agreed dashboard/features list and current feasibility

| Feature | Agreed product intent | Current evidence / implementation constraint |
|---|---|---|
| Main vehicle dashboard | Gear, speed, odometer, doors, lights/indicators, handbrake/brake, temperature and TPMS where reliable | Doors/lights/brake/handbrake/gear have useful signals. Speed requires a moving log. Odometer is decoded in Appendix I and needs cluster sanity-check. TPMS conversion is in Appendix A; use factory configuration/self-localisation/error gating. |
| Gear display | P / R / N / D | Strongly supported CAN mapping: 9=P, 10=R, 11=N, 12=D. For reverse transitions use Logic/SystemState `back` callback as the primary signal and preserve the factory camera. |
| Door status and voice alert | Individual door open/closed states and optional Arabic voice warning | Four side-door fields were physically exercised: `0=open`, `1=closed`. Trunk/bonnet were not separately tested; do not add until verified. |
| Seat belts | No seat-belt section in the launcher's product UI | Explicitly agreed: **omit both driver and passenger seat-belt UI**. Driver signals changed in one test, but that does not override the UI decision. |
| Passenger airbag | Keep passenger-airbag on/off setting/status accessible | The owner reports this control/status exists in the factory Car Settings screen. Preserve a direct entry point to that factory screen. Do not infer airbag health/cutoff from unverified frame-722 bits. |
| Wipers/windows | Not a requested launcher feature | Omit wiper/window widgets and controls. The recorded tests produced no useful transitions and the owner reports these sensors are not usable on this vehicle. |
| TPMS | Wheel pressure/temperature/status if valid | Factory decode formulas exist. Do not show a wheel unless vehicle TPMS configuration and self-localisation conditions are met and no sensor error is active. Verify units/display formatting on the car. |
| Ambient temperature | Read and display outside temperature | Factory formula is `raw * 0.5 - 40`; this log oscillated between 15°C and 16°C. Use smoothing and an unavailable sentinel check. |
| USB music | Display current track and offer transport controls | Binder binding and listener registration worked; actual play/pause/next/previous commands were not exercised. Use the factory list/activity as a fallback. |
| Bluetooth phone music — Level 3 | Phone audio continues in the background while launcher remains visible; integrated controls; physical steering-wheel buttons; ideally track/artist/album/artwork | GOC stack internally supports A2DP and parses metadata. Steering-wheel routing is statically traced via EasyConnected + Logic and needs on-device validation. External metadata and touchscreen transport are **not solved**; cover art is not present in `BaseId3Info`. See Appendix H. |
| Radio | Radio status/launch/controls | MCU radio read-all works but radio was off, and keyed `getfreq` failed. Use factory radio Activity until tuning/control is verified. |
| Video/pictures/AUX | Quick entry points; inline video was considered | Factory activities are available. Current video AIDL service has no Binder (`onBind()` returns null); inline/PiP playback inside launcher is not established. |
| Navigation | Quick access to Neshan, Google Maps and Target/Polnav; coexistence with music | Apps are installed/resolvable. `STREAM_NAVI=10` and `navi_mix_value` are useful integration clues. Test actual navigation audio mixing. Never cover reverse camera. |
| Phone UI | Incoming-call indication, contacts, recent/missed calls, answer/reject and dialling where possible | Current external call-control Binder is absent; provider cursors were null/empty in the probe. Use factory BT dialer/call UI as fallback. Do not promise a custom contact/call-log panel until access is proven. |
| App shortcuts/settings | Launch installed apps and factory system pages | Useful because Play Store is absent/non-working and factory apps are hidden from the stock app drawer. |

### Product safety/behaviour rules
- Reverse camera has priority. When reverse is detected, let the factory camera/activity/overlay own the display. Do not show a full-screen launcher overlay or consume touch input over the camera.
- Do not show unsupported or uncalibrated values as if they were reliable. In particular: vehicle speed until tested while moving; raw airbag diagnostic states; passenger belt; wipers/windows; and unverified lock/key states.
- Keep factory call UI available until a reliable external call-state and call-control mechanism is proven. Avoid duplicating the factory call screen with a second overlay.
- Prefer event callbacks over tight polling. Unregister callbacks cleanly and handle process/service disconnection/reconnection.

## 1. Device and system facts `[DEVICE]`

| Item | Value |
|---|---|
| Model / board | `AOSP on zh_dragon`, board/hardware `zh_dragon`, manufacturer `NEXELL`; CarCheck also reports `ro.board.platform=s5p4418`. Vendor properties/namespaces contain `nxp4330` references, but those references alone do **not** establish the physical SoC. |
| Android | 7.1.2 (SDK 25), build `N2G48H`, `userdebug`, `release-keys` |
| Fingerprint | `Android/aosp_zh_dragon/zh_dragon:7.1.2/N2G48H/builde02141541:userdebug/release-keys` |
| ABI | `armeabi-v7a`, `armeabi` only (**32-bit**; no arm64) |
| Screen | 952 x 600 px, density 1.5 (240 dpi bucket), ~634 x 400 dp, landscape, 7 inch |
| SELinux | `ro.boot.selinux = permissive` (so SELinux is not what blocks binder/service lookups) |
| Process context of probe | `uid=10030(u0_a30)`, `untrusted_app`, groups `net_bt_admin(3001)`, `net_bt(3002)`, `inet(3003)`, `everybody(9997)` |
| Default HOME | not set: `ResolverActivity` chooser. HOME candidates: `com.zhonghong.launcher/.activity.LauncherActivity`, the custom app, `com.wicardtech.wicardcl` (QuickSmartDrive), `com.android.settings/.FallbackHome`. `persist.sys.default.launcher` points at the factory launcher |
| Input / UI | `persist.zh.force_no_anim=true`, `persist.zh.forced_no_orient=true`, `ro.zh.inputmethodbottom=30` |
| Audio | `ro.zh.multi.audio_channel=true`; `persist.sys.max_*` (music, navi, tts, voice_call, alarm, ring, system, notification, bluetooth_sco, dtmf) are all **30**; `persist.sys.default_navi=18`, `persist.sys.default_voice_call=20`. A custom navigation audio stream exists: `STREAM_NAVI = 10` `[CODE]` |
| Locale / TZ | `fa-IR`, `Asia/Tehran` |
| Media codecs (boot props) | ext audio `ape,wma,ac3`; ext video `avi,flv,ts,mkv,rm,rmvb,asf,wmv` |
| Storage | internal `/sdcard` (FUSE, `/storage/emulated`); USB sticks appear as `/storage/udisk1`, `/storage/udisk2` (vfat, mounted from `/mnt/media_rw/udiskN`). App-private dirs on USB: `/storage/udiskN/Android/data/<pkg>/files` are writable; the USB root generally is not for a normal app |
| Misc facts | `persist.phone.easyconnect=true` (phone-mirroring app present); `sys.btcall`, `sys.carlifevr`, `zh.navi.pidlist` props exist (see section 8); `persist.sys.usb.config=adb` and a functionfs `adb` mount exist in the property/mount tables (reported as facts only; no developer-options UI is exposed to the user) |

### Hardware ports `[DEVICE]`
Kernel props: `ro.kernel.android.gps=ttyAMA0`, **`ro.kernel.android.mcu=ttyAMA2`**, `ro.kernel.android.bt=goc_serial`, `ro.mcu.baudRate=2`.
`/dev/ttyAMA0..4` are `crw-rw-rw- system system` (world read/write). `ttyAMA0` carries standard NMEA GPS sentences (`$GNGGA…`). `ttyAMA2` is the MCU link (first run sample began `FF AA 50 00 …`, second run was idle). `ttyAMA1` carries C0-delimited binary frames (looks SLIP-like, e.g. `C0 D3 74 00 B8 13 05 01 02 00 01 00 FB 69 C0`), purpose not identified. `ttyAMA3`/`ttyAMA4` idle. `/dev/i2c-*` are root-only. `usb_i2c` is world rw (purpose unknown).
**Do not read or write these ports in production**: the factory MCU service owns them, and reading steals bytes from it. Use the binder interfaces below instead.

### System services registered in ServiceManager (`service list`) `[DEVICE]`
`_logic_service` (`com.logic.common.aidl.ILogicInterface`), `zhonghong.mcu` (`android.zhonghong.IMcuService`), `zhupdate_service` (`com.zhonghong.update.aidl.IUpdateInterface`). The names `zhbt_service` and `zh_can_service` that the factory code looks up were **not** present in either `service list` snapshot, see section 3.

### Installed packages (non-AOSP) `[DEVICE]`
All factory packages are `SYSTEM`; most run as `android.uid.system`. Third party: `com.estrongs.android.pop` (ES File Explorer), `com.xbrowser.play` (X Browser), `com.chartcross.gpstest` (GPS Test), `com.polstar.polnav6` (navigation, "Target"), `org.rajman.neshan.traffic.tehran.navigator` (Neshan), `net.easyconn` (EasyConnected phone mirroring), `com.persian.keyboard…`, plus the user-installed launchers. Full package list with versions is in section 2.

### Things a normal app can / cannot do on this unit `[DEVICE]`
- Can: be a HOME candidate (it appears in the chooser), launch every factory activity and the hidden system apps (ES, X Browser, GPS Test, Android Settings), bind exported factory services, read exported content providers, read `Settings.System`, read system properties.
- Factory apps are not visible in any system app drawer; they only appear through a launcher that lists all `LAUNCHER` activities.
- Installing APKs is only possible through the ES file explorer / package installer launched from a custom launcher (no store, no stock file manager UI).
## 2. Package inventory (factory + system)

| Package | Version | Shared UID | Role | Exported components (services / providers / receivers / activities with custom action) |
|---|---|---|---|---|
| `android.ext.services` | 1 | - | AOSP ext services | svc `Ranker` [android.service.notification.NotificationRankerService] |
| `android.ext.shared` | 1 | - | AOSP ext shared | - |
| `com.car.phoneprovider` | 20180720 | - | Phone-book / call-log content provider `com.car.phone_data` | provider `com.car.phone_data` |
| `com.chartcross.gpstest` | 1.5.4 | - | GPS Test (3rd party) | rcv `ImIdShareBroadCastReceiver` [com.inmobi.share.id]; rcv `ReferrerReceiver` [com.android.vending.INSTALL_REFERRER] |
| `com.estrongs.android.pop` | 3.1.0.3 | - | ES File Explorer (3rd party) | svc `OBEXFtpServerService` [android.intent.action.START_OBEX_FTP_SERVER]; provider `com.estrongs.files`; act `FileExplorerActivity` [org.openintents.action.VIEW_DIRECTORY,com.estrongs.android.SHOW_DISK_USAGE]; act `ESFileSharingActivity` [android.intent.action.SEND,android.intent.action.SEND_MULTIPLE]; act `PopNoteEditor` [android.intent.action.EDIT]; act `FileChooserActivity` [com.estrongs.action.PICK_FILE,com.estrongs.action.PICK_FILE,com.estrongs.action.PICK_DIRECTORY,com.estrongs.action.PICK_DIRECTORY,android.intent.action.CREATE_SHORTCUT]; act `ESContentChooserActivity` [android.intent.ac |
| `com.polstar.polnav6` | @0x7f040002 | - | Navigation (3rd party) | svc `PolnavELService` [com.polstar.polnav6.IPolnavELService] |
| `com.xbrowser.play` | 3.4.5 | - | X Browser (3rd party) | act `BrowserActivity` [android.intent.action.WEB_SEARCH,android.intent.action.WEB_SEARCH,android.intent.action.MEDIA_SEARCH,android.intent.action.SEARCH,android.intent.action.SEND] |
| `com.zh.auxin` | 202110211840 | android.uid.system | AUX-in screen | - |
| `com.zh.settings` | 202401051446 | android.uid.system | System settings UI (sound/EQ, display, wifi, system, language) | svc `SetService` [com.zhonghong.action.set]; act `MainActivity` [com.zhonghong.action.set]; act `EqActivity` [com.zhonghong.action.eq] |
| `com.zh.test` | 202301041609 | android.uid.system | Factory test tool (CmdService, boot receiver). Not exported | - |
| `com.zh.yzradio` | 202211090303 | android.uid.system | Radio UI (FM/AM, RDS). Talks to the radio through the MCU service | - |
| `com.zhonghong.aidlserver` | 1.0 | - | System state broker (ACC, volume, BT state, back state, headlamp, network, TUID) with callbacks + generic get/set provider registry | svc `SystemStateService` [com.zhonghong.aidlserver.service.SystemStateService]; svc `SystemStateService2` [com.zhonghong.aidlserver.service.SystemStateService2] |
| `com.zhonghong.bluetooth` | 202401051453 | android.uid.system | Persistent Bluetooth/phone manager. In this build the active backend is **GOC** (`BtSDK.Config(1)` → `GocBluetoothAdapter` / `GocJavaController`); NForetek adapter code exists as an unused alternative. Exported `btinfo` provider and EasyConnected receiver. The apparent `BtService` Binder API is not registered on this unit; see Section 3.4 and Appendix H. | svc `BtCtrlManagerService` [com.zhonghong.bluetooth.START_BtCtrlService]; provider `com.zhonghong.bluetooth.provider.btinfo`; rcv `UpdateSystemReceiver` [zhonghong.intent.action.START_UPDATE,zhonghong.intent.action.END_UPDATE,com.zhonghong.bluetooth.call_last_number]; rcv `EasyConnectedReceiver` [net.easyconn.bt.checkstatus,net.easyconn.bt.connected,net.easyconn.app.quit,net.easyconn.a2dp.release,net.easyconn.a2dp.acquire,net.easyconn.bt.connect,net.easyconn.connection.break]; act `MainActivity` [android.bluetooth_btsettings,android.bluetooth_btdial]; act `MusicActivity` [android.bluetooth_btm |
| `com.zhonghong.can` | 2024.01.05 | android.uid.system | CAN decoder service: raw CAN frames -> decoded vehicle state (doors, lights, TPMS, ...). Exposed as bound service | svc `CanService` [com.zhonghong.intent.action.can] |
| `com.zhonghong.carsetting` | 202401171303 | android.uid.system | Car settings UI (TPMS page, car status, ambient temp...). Shows how raw CAN values are converted to display values | - |
| `com.zhonghong.launcher` | 202401051430 | android.uid.system | Factory launcher (home screen, app slots, status widgets) | act `MediaActivity` [com.zhonghong.launcher.MEDIA_ACTION]; act `NaviActivity` [com.zhonghong.launcher.NAVI_ACTION] |
| `com.zhonghong.logic` | 202401051107 | android.uid.system | System logic: ACC/power/reverse(parking)/AVM/illumination states, audio source & focus arbitration, TFT/backlight, reverse transparent layer, key events. Registers system service `_logic_service` | svc `LogicService` [com.zhonghong.logic.service.LogicService] |
| `com.zhonghong.mcu` | 20170117 | android.uid.system | MCU firmware update service only (not the MCU data path) | svc `McuUpdateService` [com.zhonghong.mcu.START_MCUUPDATE]; svc `McuCtrlServer` [com.zhonghong.action.mcuupdate] |
| `com.zhonghong.mediaconfigtool` | 202009281030 | android.uid.system | Media config tool (USB receiver) | - |
| `com.zhonghong.mediascan2` | 20230321.1.0 | - | Media scanner + exported content provider `com.zhonghong.media` (library of audio/video/image/txt) | provider `com.zhonghong.media` |
| `com.zhonghong.screensaver` | 202209191426 | android.uid.system | Screensaver service (exported action) + USB receiver | svc `ScreenService` [com.zhonghong.screensaver.ScreenService] |
| `com.zhonghong.update` | 202302071016 | android.uid.system | System update UI + exported `UpdateService` (also registered as `zhupdate_service`) | svc `UpdateService` [com.zhonghong.ctrl_update] |
| `com.zhonghong.uuidtool` | 202011241500 | android.uid.system | UUID/device identity tool | - |
| `com.zhonghong.yzmusic` | 202303211539 | android.uid.system | USB music player + exported `UsbMusicService` (control & metadata) | svc `UsbMusicService` [com.zhonghong.usb_music_action]; act `MainActivity` [com.zhonghong.usb_music_activity,com.zhonghong.usb_music_list,com.zhonghong.intent.action.music] |
| `com.zhonghong.yzpicture` | 202206021631 | android.uid.system | USB picture viewer | act `MainActivity` [com.zhonghong.usb_music_activity] |
| `com.zhonghong.yzvideo` | 20240105.01 | android.uid.system | USB video player. Factory activity is available; the currently declared `VideoService` returns `null` from `onBind()`, so external AIDL control is not available in the inspected source. | svc `VideoService` [com.zhonghong.usb_video_action]; act `MainActivity` [com.zhonghong.video_activity] |

Note: in manifests, a service/receiver with an `<intent-filter>` and no explicit `exported` attribute is exported by default on this SDK level. The table lists those as exported.

## 3. How to connect to everything (the connection map)

### 3.0 Reachability matrix

| # | Unit / data | How to reach it | Status |
|---|---|---|---|
| 1 | Vehicle state from CAN (doors, lights, TPMS, ambient temp, ODO, vehicle speed, gear lever, PEPS/keyless, wipers, locks, windows) | `bindService` to `com.zhonghong.can` / `com.zhonghong.can.CanService`, AIDL `ICanInterface` (section 3.1) | `[DEVICE]` bind/read/callback confirmed from a normal third-party app; check Section 3.1 and Appendix G for signal-by-signal confidence |
| 2 | System state: ACC, power, reverse (parking), AVM, illumination, running, audio source/focus | System service `_logic_service` via hidden `ServiceManager.getService` (section 3.2) | `[DEVICE]` getter calls and callbacks work from a normal third-party app; command writes remain untested. Easy fallback: Settings.System `back_state` (section 3.9) |
| 3 | MCU: volume, mute, EQ, balance/fade, brightness, TFT, beep, radio, hard keys, power/ACC/ILL JSON | System service `zhonghong.mcu` (section 3.3) | `[DEVICE]` domain read-all and selected keyed reads work from a normal third-party app; write operations remain untested |
| 4 | Bluetooth phone/music control | Internal GOC stack in `com.zhonghong.bluetooth`; the only currently promising external steering-key route is `net.easyconn.a2dp.acquire` → `BtMusicAudioHelper` → Logic source callback (Section 3.4 / Appendix H). | `[CODE]` path traced; **not yet runtime-tested**. `zhbt_service` lookup is null and the ordinary declared BT service binds to no Binder. Touchscreen BT transport and external ID3 access remain unresolved. |
| 5 | BT status/metadata, BT music info | Broker callbacks / `Settings.System` for coarse connection and call state; GOC `MI` parser inside the BT process for title/artist/album; `btinfo` provider (3.4). | `[DEVICE]` coarse connection/call signals readable; provider metadata is null/contacts empty. GOC internally parses ID3, but no usable external interface exposes it to the launcher. |
| 6 | Phone book + call log | ContentProvider `content://com.car.phone_data/{contacts,call_log,contacts_favorite}` (3.4) | `[CODE]` exported, no permission; `[DEVICE]` contacts/call-log queries returned null cursors in the probe, so custom lists are not yet supported. |
| 7 | USB music (metadata + play/pause/next/prev) | bind `com.zhonghong.yzmusic` / `com.zhonghong.music.model.service.UsbMusicService` (3.5) | `[DEVICE]` bind and listener registration succeeded; actual transport buttons remain untested. |
| 8 | USB video control | Factory activity `com.zhonghong.video_activity`; declared service `com.zhonghong.yzvideo.service.VideoService` (3.5) | `[DEVICE]` bind request returned true but no connection; `[CODE]` current service `onBind()` returns null. Use factory activity; embedded controls are not available yet. |
| 9 | Media library (audio/video/image/txt on USB) | ContentProvider `content://com.zhonghong.media/{audio,video,image,txt,id3,scan_state}` (3.5) | `[CODE]` exported |
| 10 | System state broker (ACC, volume, BT state, back, headlamp, network, TUID) | bind `com.zhonghong.aidlserver.service.SystemStateService` / `SystemStateService2` (3.6) | `[CODE]` exported (process `:systemstate`) |
| 11 | Radio high-level AIDL / BT-music AIDL | AIDL interfaces `IRadioAidlInterface`, `IBtMusicAidlInterface` exist in the music app | `[NOT FOUND]` no server for them in the provided APKs; use MCU domain 1 for the radio |
| 12 | Key / MultiScreen / Navi / ExtraAudioDevice AIDL (`KeyInterface`, `ScreenInterface`, `NaviInterface`, `ExtraDeviceInterface`) | SDK interfaces bundled in several apps | `[NOT FOUND]` no server and no user in the provided APKs |
| 13 | Screen off / beep / other broadcasts | Broadcast intents received by the Logic app (3.8) | `[CODE]` |
| 14 | Factory screens (radio, USB music/video/pictures, BT dial/music/settings, system settings, EQ, car settings, AUX, update) | `startActivity` with the actions in 3.8 | `[CODE]` |
| 15 | Phone call state quick check | system property `sys.btcall` (`"true"` during a BT call) | `[CODE]` set by BT app; easy to read |
| 16 | BT connection / signal / battery for status bar | `Settings.System` keys `bt_connected`, `key_bluetooth_state`, `key_signal_state`, `key_battery_state` | `[CODE]` |
| 17 | Raw serial ports | `/dev/ttyAMA0..4` | `[DEVICE]` world rw, but **do not use** (section 1) |

**Standard AIDL call recipe (everything marked "standard AIDL" below).**
```
Parcel d = Parcel.obtain(), r = Parcel.obtain();
d.writeInterfaceToken(DESCRIPTOR);
d.writeInt(...)/writeString(...)/writeStrongBinder(cb);       // arguments in declared order
binder.transact(TRANSACTION_CODE, d, r, 0);
r.readException();                                             // then read the return value from r
```
A callback is a `Binder` subclass with `attachInterface(null, CALLBACK_DESCRIPTOR)`; in `onTransact(code,data,reply,flags)` call `data.enforceInterface(CALLBACK_DESCRIPTOR)`, read the arguments, then `reply.writeNoException()`.
Hidden service lookup: `Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name)` (reflection is allowed on SDK 25).
Always run binder calls off the main thread.

---

### 3.1 CAN service (`com.zhonghong.can`)  [CODE]

- Bind: `new Intent().setClassName("com.zhonghong.can","com.zhonghong.can.CanService")` (intent action `com.zhonghong.intent.action.can`). Factory clients first try system service name `zh_can_service`, which is not registered on this unit `[DEVICE]`, then fall back to this bind.
- Descriptor `com.zhonghong.cancommon.ICanInterface` (standard AIDL):

| Code | Method |
|---|---|
| 1 | `registerCallback(ICanDataCallback)` |
| 2 | `unregisterCallback(ICanDataCallback)` |
| 3 | `execCmd(int cmd, String value)` |
| 4 | `execExtraCmd(int cmd, String value, String extra)` |
| 5 | `ElementData getElementDataInfo(int moduleType)` |

- Callback descriptor `com.zhonghong.cancommon.ICanDataCallback`: code 1 `onCanDataNotify(int moduleType)` (call `getElementDataInfo(moduleType)` again when it fires).
- `getElementDataInfo` reply (after `readException()`): `int hasValue`; if non-zero an `ElementData` follows: `int size`, then `size` times { `int frameId`; `int len` (written as a Parcel byte); if `len>0` a Parcel byte-array (`int length` + bytes) }.
- Module types: **1** CarSet (frames 963, 800, 722, 1219, 1218), **2** TPMS (1042, 1043), **3** CarStatus (564, 577), **4** AmbientTemp (695).
- Frame layout: `byte[]` of a frame = 3 header bytes (frame id low/high + 1 byte) then 8 data bytes. Data bit access is Intel/little-endian over the data bytes:
  `value = sum( bit(i) << i )` where bit `i` is at absolute bit position `(3 + byteIdx) * 8 + startBit + i` of the array (bit 0 = LSB of each byte). Verified identical to the factory routine on 22,000 random inputs.
- The full field table (every named signal with byte/bit/length) is in **Appendix A**.
- **Decoded semantics confirmed from the factory car-settings UI code (`com.zhonghong.carsetting`)** `[CODE]`:
  - Doors (frame 564, byte 1, bits 0..5 = front-left/driver, front-right, rear-left, rear-right, trunk, bonnet): **value 0 = OPEN, 1 = closed**.
  - Hand brake (564, byte 4 bit 2) `==1` = engaged. Reverse gear (564, byte 4 bit 3) `==1`.
  - Tyre pressure bar = `ceil(raw * 1.378 + 100) / 100` (one decimal). Tyre temperature °C = `raw - 40`.
  - Tyre pressure status: 0 normal, 1 very low, 2 slightly low, 3 slightly high, 4 very high. Tyre temperature status: 0 normal, 3 slightly high, 4 very high. `TPMSError==1` sensor error; `AirLeakError==1` leak; `SensorLowBattery==1`.
  - A wheel is shown only when `PEPS_TPMSSystemConfiguration == 1` (frame 1043 byte 5 bits 0-1) **and** that wheel's `SelfLocalizationStatus == 1` (frame 1043 byte 4, 2 bits per wheel) **and** no `TPMSError`.
  - Ambient temperature °C = `raw * 0.5 - 40`; raw `>= 254` means not available.
  - `Start_Switch_Status` and `PowerDistributionStatus`: values 2 or 3 mean "on".
- **Device-tested:** lamp switch/output polarity for tested position/low/high/fog lamps; four side-door open/closed polarity; brake pedal; handbrake; and the strongly supported gear-selector mapping (see Appendix G). The driver-belt bits changed in the probe, but **the agreed launcher UI must omit the entire seat-belt section**. **Still unresolved:** passenger-belt signals; speed while moving/units; `KeyPositionStatus`; airbag diagnostic semantics; wiper/window signals (no useful physical transitions were established). Odometer interpretation is updated in Appendix I: the raw 32-bit value and factory formula yield 34,984 km; compare with the instrument cluster before treating it as field-verified.
- `execCmd` / `execExtraCmd`: the code routes commands (range 100–199, "CarSet", CAN module id 1333, 7 data bytes) to a sender registry, but **no concrete sender class is shipped** in this APK `[NOT FOUND]`. Assume it does nothing. This unit's CAN integration is effectively **read-only**.

---

### 3.2 Logic service (`_logic_service`)  [CODE] + [DEVICE]

- Obtain: `ServiceManager.getService("_logic_service")` (listed in `service list`).
- Descriptor `com.logic.common.aidl.ILogicInterface` (standard AIDL):

| Code | Method |
|---|---|
| 1 | `registerLogicCallback(int domain, ILogicCallback cb)` |
| 2 | `unregisterLogicCallback(int domain, ILogicCallback cb)` |
| 3 | `setCmd(String cmd, String value, String extra)` |
| 4 | `String getValue(String key, String extra)` |

- Callback descriptor `com.logic.common.aidl.ILogicCallback`: code 1 `onEventChanged(String event, String value, String extra)`. In this build `registerLogicCallback` registers the callback for **all** events whatever the `domain` argument (declared domains: 1 logic state, 2 source, 3 key, 4 setting).
- `setCmd` implemented: `setSourceId` (value = source id as decimal string), `releaseSourceId`, `closeSourceId`. The constants `setBright`, `setVolume`, `setAutoBright`, `setVolumeChange` exist but are **not handled** by this binder; use the MCU service or `Settings.System` for brightness/volume.
- `getValue` implemented (returns the decimal/boolean text): `getSourceId`, `getThirdSourceId`, `hasFocusById` (extra = id), `getFocusStateById` (extra = id), `getFocusState`, `getAvmState`, `isAccOn`, `isAuxState`, `isIllon`, `isInBackState`, `isPowerOn`, `isRunning`.
- Events (values are strings such as `"true"/"false"` or ints): `onAccStateChanged`, `onPowerStateChanged`, `onBackStateChanged` (reverse gear/parking), `onAvmStateChanged`, `onRunningStateChanged`, `onIllStateChanged` (illumination/lights switch), `onAuxStateChanged`, `onSourceChanged`, `onThirdSourceChanged`, `onFoucsStateChanged`, `receiverFoucsStateChanged`, `onTftStateChanged`, `onBrightChanged`, `onBrightModeChanged`, `onKeyEvent`, `onMediaButtonPressed`.
- Audio source ids: radio 0, music 1, video 2, BT music 3, online music 4, online radio 5, news 6, online program 7, online video 8, SD music 9, SD video 10, AUX 11, CarLife 12, BT phone 13, voice 14, third-party audio app 15, null 4096, off(ARM) 4097, off(MCU) 4098, other app 4099.
- Reverse camera behaviour `[CODE]`: the video switch is done by the hardware/factory logic. While reversing the Logic app writes `Settings.System "back_state" = "true"/"false"` and shows a transparent touch-blocking layer (`BackcarTransParentLayer`). A third-party launcher must not cover the camera view; it can only observe `back_state` / `onBackStateChanged`. The factory logic does not obviously duck other apps' audio in the code read (not verified).

---

### 3.3 MCU service (`zhonghong.mcu`)  [CODE] + [DEVICE]

- Obtain: `ServiceManager.getService("zhonghong.mcu")` (listed in `service list`). Descriptor `android.zhonghong.IMcuService`.
- **Non-standard wire format (no exception header in replies).**

| Code | Call | Request parcel | Reply |
|---|---|---|---|
| 2 | `getTest()` | token | int |
| 3 | `obtainInfo(domain, cmd, in, out)` | token, `writeInt(domain)`, `writeInt(cmd)`, then the raw data of the `in` parcel appended | the `out` parcel data followed by a final `int` result (1 = ok). Read `out` as the first `dataSize-4` bytes; read the result at `dataSize-4` |
| 4 | `sendInfo(domain, cmd, p)` | token, `writeInt(domain)`, `writeInt(cmd)`, raw data of `p` appended | one `int` (1 = ok) |
| 5 | `registDataChangedListener(domain, IDataChangedListener)` | token, `writeInt(domain)`, `writeStrongBinder(listener)` | int |
| 6 | `unregistDataChangedListener(domain, listener)` | same | int |

  Listener descriptor `android.zhonghong.IDataChangedListener`: code 2 `notify(int domain, int cmd, int arg, Parcel data)`.
- **JSON conventions (all current settings/state live behind these):**
  - *Write*: `sendInfo(domain, 1, p)` where `p` contains `writeByteArray(jsonBytes)` and the json is `{"KEY": value}` (one or more keys).
  - *Read all keys of a domain*: `obtainInfo(domain, 1, in, out)` with `in = writeByteArray("{\"all\":0}")`. The `out` data is: `int len`, `int type`, then `len` bytes; the text is the first `len-1` bytes (trailing NUL), and it is a JSON object.
  - *Read one key*: same but `in` = `{"KEY":0}`; the text returned is the value of that key.
  - Domain ids: **1** radio, **2** settings (audio/display), **3** system state, **4** MCU hard-key info (radio seek-left/right commands are also sent here), **5** CAN info (MCU level), **6** customer info, **7** ATest, **8** offline config (`obtainInfo(8,0)` with `{"all":0}`), **4608** system key (cmd 4352 register key, 4353 clean key), **4864** touch.
- **Domain 2 (settings) keys `[CODE]`** (read and write by the same key): `SYS_VOL`, `SYS_MUTE`, `SYS_BEEP`, `SYS_BEEPVOL`, `SYS_BTVOL`, `SYS_TTSVOL`, `SYS_GPSVOL`, `SYS_TBOXVOL`, `defvol`, `carspeed_volume` (speed-dependent volume), `EQ40`, `EQ80`, `EQ500`, `EQ1k`, `EQ5k`, `EQ16k`, `bass`, `SYS_MID`, `treb`, `SYS_LOUD`, `audiofade`, `audiobal`, `3D_AUDIO_SW`, `SYS_AUDIOMODE`, `FM_LIMITER`, `AM_LIMITER`, `brightness`, `backlight`, `vicebacklight`, `setautobrighton`, `TFT1`, `TFT2`, `SYS_TIME12H`, `LANG_SEL`, `UNLOCK_STATE` (read-only door unlock state), `WELCOME_REQ`, `PRODUCT_INFO`, `CAN_COMPANY`, `SYS_FLAG`, `SYS_SYSFLAG2`, `SYS_ARMSAVE1..3`, `RRS_AUTO_LEVEL`, `RRS_RTURN_EN`, `RRS_AUTO_REQ`. The full list of proxy methods and domain/command numbers is in Appendix B.
- **Domain 3 (system) keys** `[CODE]`: `ACC_STATE`, `POWER_STATE`, `BACK_SIGNAL` (reverse), `ILL_STATE`, `SYSREADY`, `MCU_SOURCE` (read; write via `setMcuSource`), `MCU_STATE`, `BPOWER_ON`, `BRIGHT_TURNER`, `VICEDIRVER_HEADPHONE`, `NEED_SHOWDIALOG`, `MCU_TIME`, `SYS_TIME`. Several action methods exist (power key, reset key, beep, update state, mute 1 s, navi-voice notification, animation notification) sent as JSON to domain 3; see Appendix B.
- **Domain 1 (radio) `[CODE]`:**
  - Read all: `{"all":0}` -> JSON with `getfreq` (current frequency), `freq`, `scan`, `loc`, `st` (stereo), `stind`, `aps` (auto-preset search), `RADIO_AREA`, `RADIO_SREACH_STATE`, `RADIO_PLAYSTATE`, `AUX_PLAYSTATE`, signal level `signlevel`, `HAVESIG`, RDS keys `RADIO_NAME` (station name/PS), `RADIO_TEXT`, `PTY`, `TP`, `TP_INFO`, `TA_MODE`, `RDS_ON_OFF`, `RDS_TA_MODE`, `AF`, `EON`, `INTRO`, `LAGEL_FREG`.
  - Set frequency: `{"freq": (band << 14) | (freq & 16383)}`, band **`0` = FM, `3` = AM** (the factory rejects other values; corrected in revision 3, see Appendix N). FM values are in 10 kHz units (e.g. 9500 = 95.0 MHz, range 8750-10800, step 10), AM in kHz (range 522-1620, step 9).
  - Seek next/previous station: domain **4**, `{"RADIO_SREACH_R":0}` / `{"RADIO_SREACH_L":0}`. Stop scan: domain 1, `{"stop_scan":0}`. Scan: `{"scan":0}`. Play state: `{"RADIO_PLAYSTATE": n}`. AUX play state: `{"AUX_PLAYSTATE": n}`. Plus loc/stereo/APS toggles, RDS on/off, PTY, TA/AF clicks, factory radio area (Appendix B).
- Hard keys (domain 4) and system keys (domain 4608): the factory UI registers listeners to receive steering-wheel / panel key events (`McuHardKeyInfo`, `SystemKeyInfo` parcels). Exact parcel layouts: see decompiled `McuHardKeyInfo`/`SystemKeyInfo` (not needed unless implementing steering-wheel buttons).
- Status: MCU read-all in domains 1, 2 and 3, plus selected keyed reads, was **confirmed from a normal third-party app**. MCU settings writes, action commands, radio tuning/control, hard-key registration and key parcel decoding remain **`[UNTESTED]`**. SELinux is permissive, but the MCU service may still impose write-side restrictions.

---

### 3.4 Bluetooth, phone book, call state  [CODE] / [DEVICE] — internal API versus usable external access

**A. Apparent control service `zhbt_service` — source API exists, but is not available on this firmware as tested.**

- The source class `com.zhonghong.bluetooth.service.BtService` contains a Binder implementation with descriptor `com.zhonghong.bluetooth.aidl.IBtCtrlInterface`: `setCmd(int cmd, String value, String extra)`, `getValue(String key, String extra)`, `registerCallback(int domain, IBtDataCallback cb)`, and `unregisterCallback(int domain, IBtDataCallback cb)`.
- That class would register `zhbt_service` with `ServiceManager.addService(...)`, and the source `BtSDK.initData()` attempts to start it. However, **`BtService` is not declared in the supplied Bluetooth app manifest**. The manifest-declared `BtCtrlManagerService` returns `null` from `onBind()`.
- On the real unit, `ServiceManager.getService("zhbt_service")` returned `null` in the CarCheck snapshots. Do not implement against this Binder or describe its commands/getters as an available third-party API.
- For source archaeology only, the unregistered Binder class contains command constants for pairing/connect, A2DP/HFP, music transport, ID3, phone calls, contacts and call logs (see Appendix C). These are **not evidence that a normal launcher can invoke them**.
- The BT data callback class and domains (settings/music/phone/contact/call log) are similarly internal source definitions, not a live integration contract for this launcher.

**B. Content provider `content://com.zhonghong.bluetooth.provider.btinfo`** (authority `com.zhonghong.bluetooth.provider.btinfo`, exported, no permissions):
- `/btbase` -> columns `id, state, devname, power, signal, battery, talkname, talknum, micincar, micmute`
- `/btmusic` -> `id, title, artist, album, duration, btmusicstate`
- `/btcontacts` -> `id, name, num`
- `/ctrlbt` update channel (`enable_bt`).
  **Device result:** `btbase` and `btmusic` each returned one row, but useful metadata fields were null; `btcontacts` returned zero rows. These tables do not currently support a useful custom phone/BT-music UI.

**C. Content provider `content://com.car.phone_data`** (app `com.car.phoneprovider`, exported, no permissions):
- `/contacts` and `/contacts_favorite` -> `_id, address, name, number, pinyin, storage, type`
- `/call_log` -> `_id, active_start_time, address, end_time, name, number, start_time, timestamp, type` (type codes: 5 missed, 6 received, 7 dialed)
- `/force_contactes` and `/force_calllog` trigger a refresh.

**D. Cheap signals that need no binder:**
- System property `sys.btcall` = `"true"` while a BT call is active, `"false"` otherwise (set by the BT app).
- `Settings.System`: `bt_connected`, `key_bluetooth_state`, `key_signal_state`, `key_battery_state` (written by the factory status-bar SDK).
- `SystemStateInterface` (3.6): `getBluetoothConnectState()`, `getBluetoothInCallState()` and callbacks `notifyBTState`, `notifyBTPhoneState(int)`.
- Broadcast `com.zhonghong.bluetooth.call_last_number` is handled by an exported receiver in the BT app (`UpdateSystemReceiver`; the name suggests "call last number", behaviour not read in detail).

**E. Factory screens:** actions `android.bluetooth_btdial` (dialer), `android.bluetooth_btsettings` (BT settings), `android.bluetooth_btmusic` (BT music) on `com.zhonghong.bluetooth`.

**F. Active backend correction:** the source contains GOC, NForetek and IVT implementations, but `BtApp.onCreate()` calls `BtSDK.Config(1)`. `BlueToothManager.init(type=1)` selects `GocBluetoothAdapter`, and `ControllerFactroy.init(1)` selects `GocJavaController`. Therefore the active path in this firmware is **GOC**, not NForetek. NForetek classes in the APK are an alternative code path and must not be treated as the active backend. Full details and the GOC command/event map are in Appendix H.

---

### 3.5 Media: USB music, USB video, library, radio, AUX  [CODE]

**USB music service** — bind `com.zhonghong.yzmusic` / `com.zhonghong.music.model.service.UsbMusicService` (action `com.zhonghong.usb_music_action`, exported). Descriptor `com.zhonghong.mediaaidl.usbmusic.IUsbMusicAidlInterface` (standard AIDL):
1 `registerDataChange(IUsbMusicAidlListener)`, 2 `unregisterDataChange`, 3 `boolean isPlaying()`, 4 `String getTitle()`, 5 `String getAlbum()`, 6 `String getArtist()`, 7 `setMusicSource()` (switch the audio source to USB music), 8 `next()`, 9 `prev()`, 10 `playPause()`, 11 `play()`, 12 `pause()`.
Listener `com.zhonghong.mediaaidl.usbmusic.IUsbMusicAidlListener`: 1 `onId3(String title, String artist, String album)`, 2 `onPlayState(boolean playing)`.
There is no "play this track" call; choosing a specific file is done in the factory screen (`com.zhonghong.usb_music_list`).

**USB video service** — the current manifest declares `com.zhonghong.yzvideo.service.VideoService` (action `com.zhonghong.usb_video_action`), but its `onBind()` returns `null` in the supplied source. The older `com.zhonghong.video.model.service.VideoService` class has a Binder implementation but is not the active manifest component/current companion package. The probe bind request returned `true`, but `onServiceConnected` did not arrive within 5 seconds. Use the factory video Activity as a fallback. Video playback itself is supported by the factory app; **inline/PiP video inside the launcher and external transport control are not established**.

**Radio AIDL / BT-music AIDL** `[NOT FOUND]`: `IRadioAidlInterface` (`getFreq`, `isAMBand`, `isPlaying`, `setRadioSource`, `playPause`, `nextStation`, `prevStation`, `nextStep`, `prevStep`, `nextSearch`, `prevSearch`, `fMAMSwitch`) and `IBtMusicAidlInterface` (title/artist/album/play/pause/next/prev/`setBtMusicSource`) are defined in the music app, but the packages that would serve them (a radio service, `com.zhonghong.bluetooth.BtMusicService`) are not in the provided APKs. The launcher/logic code starts a service with action `com.zhonghong.radio.radioservice.action` (extra `command`), which does not exist in the provided APKs either. **Control the radio through MCU domain 1 (section 3.3).**

**Media library provider** `content://com.zhonghong.media` (exported, no permissions), tables:
`audio(_id, path, name, parent_path, title, album, artist)`, `id3(path, title, album, artist, name, parent_path, uuid, file_size)`, `video(_id, path, name, parent_path)`, `image(_id, path, name, parent_path)`, `txt(_id, path, name, parent_path)`, `status(path, state)`; URI patterns `audio`, `audio/*`, `video`, `video/*`, `image`, `image/*`, `txt`, `txt/*`, `id3`, `scan_state`, `scan_state/*`.

**AUX** — activity `com.zh.auxin` (VIEW). Source id 11 in Logic; MCU key `AUX_PLAYSTATE` (domain 1). `Settings.System system_state_aux`.

**Picture viewer** — `com.zhonghong.yzpicture` (also answers `com.zhonghong.usb_music_activity`).

---

### 3.6 System-state broker (`com.zhonghong.aidlserver`)  [CODE]

- Bind `com.zhonghong.aidlserver` / `com.zhonghong.aidlserver.service.SystemStateService` (action `com.zhonghong.aidlserver.service.SystemStateService`, exported, process `:systemstate`). A second service `SystemStateService2` serves the generic interface.
- `com.zhonghong.aidlib.state.SystemStateInterface`: 1 `registerCallBack(SystemStateCallBack)`, 2 `unregisterCallBack`, 3 `int getTuidNumber()`, 4 `boolean getNetworkState()`, 5 `boolean getBluetoothConnectState()`, 6 `boolean getBluetoothInCallState()`.
- `com.zhonghong.aidlib.state.SystemStateCallBack` (callback): 1 `notifyHeadlampState(boolean)`, 2 `notifyVOL(int)`, 3 `notifyBTPhoneState(int)`, 4 `notifyACC(boolean)`, 5 `notifyBTState(boolean)`, 6 `notifyNetworkState(boolean)`, 7 `notifyBackState(boolean)`, 8 `notifyPowerState(boolean)`.
- `SystemStateInterface2` (generic category/type store): `registerCallback(int, StateCallback)`, `registerServiceProvider(int, ServiceProvider)`, `getBooleanInfo/getIntInfo/getStringInfo(int,int)`, `setBooleanInfo/setIntInfo/setStringInfo(int,int,value)`, `notify*Info`. **Device result:** binding and descriptor retrieval succeeded; its generic getter/setter/provider methods were not exercised, so data availability remains `[UNTESTED]`. Known type ids (`ZServiceProviderTypes`): ACC 1001, back-car 1002, power 1003, volume 1004, mute 1005, headlamp 1006, main TFT backlight 1007 (second TFT 1008), illumination-on 1009, main TFT brightness 1010 (second 1011), brake warning 1012; Bluetooth: connect state 2001, phone state 2002, music state 2003, HFP 2004, A2DP 2005; network connect state 3001, TBOX UUID 3002; multiscreen 4001-4005 (vice screen state, screen lock, main/vice app name, headphone state). Categories: system 1000, Bluetooth 2000, network 3000, multiscreen 4000.
- Value for a quick implementation: use this broker's callbacks for ACC/back/headlamp/volume instead of polling. **Bluetooth caveat:** type `2005` is named as A2DP-connect state in `ZServiceProviderTypes`, but `BtCtrlManager` does not implement that type in `getBoolean()` (it implements only the coarse BT connect and phone-state types). Do not use a `false` response for type 2005 as proof A2DP is disconnected. `getBluetoothConnectState()` / `bt_connected` are coarse HFP/A2DP connection indicators, not an A2DP-only signal.

---

### 3.7 Other services / apps

| App | Component | What it offers |
|---|---|---|
| `com.zhonghong.update` | `UpdateService` (action `com.zhonghong.ctrl_update`, exported); also system service `zhupdate_service` (`com.zhonghong.update.aidl.IUpdateInterface`) | system update control. **Do not use** from a launcher |
| `com.zhonghong.screensaver` | `ScreenService` (action `com.zhonghong.screensaver.ScreenService`, exported) | start/stop the screensaver (started as a service; extras not analysed) |
| `com.zh.settings` | `SetService` (action `com.zhonghong.action.set`) + activities Sound/System/Wifi/Display/Field/EQ | system settings screens |
| `com.zhonghong.mcu` | `McuUpdateService`, `McuCtrlServer` | MCU firmware update only. **Do not use** |
| `com.zh.test` | `CmdService`, boot receiver | factory test tool, not exported |
| `com.zhonghong.mediaconfigtool`, `com.zhonghong.uuidtool` | USB receivers | factory tools |

---

### 3.8 Intents and broadcasts

**Activities (use `startActivity(new Intent(action))` or explicit component):**

| Action / component | Opens |
|---|---|
| `com.zhonghong.intent.action.music`, `com.zhonghong.usb_music_activity` | USB music player |
| `com.zhonghong.usb_music_list` | USB music track list |
| `com.zhonghong.video_activity` | USB video |
| `com.zh.yzradio/.MainActivity` (VIEW) | Radio |
| `android.bluetooth_btdial` / `android.bluetooth_btsettings` / `android.bluetooth_btmusic` | BT dialer / BT settings / BT music |
| `com.zhonghong.action.set` | system settings; `com.zhonghong.action.eq` = equalizer |
| `com.zhonghong.carsetting/.MainActivity` | car settings (TPMS etc.) |
| `com.zh.auxin/.MainActivity` | AUX-in |
| `com.zhonghong.launcher.MEDIA_ACTION`, `com.zhonghong.launcher.NAVI_ACTION` | factory launcher media / navigation pages |
| `com.zhonghong.update/.MainActivity`, `com.zh.test/.MainActivity`, `com.zhonghong.logic/.MainActivity` | update / factory test / logic status |
| `com.estrongs.android.pop`, `com.xbrowser.play`, `com.chartcross.gpstest`, `com.android.settings` | file manager (also the way to install APKs), browser, GPS test, Android settings |

**Broadcasts the Logic app listens for (any app can send them)** `[CODE]`:
- `com.zhonghong.logic.close_screen` -> turns the display (TFT) off.
- `com.zhonghong.android.key` with int extra `data = 4608` -> system beep.
- `action.radio.RDS_TRAFFIC` -> wakes/opens the screen for RDS traffic announcements.
- It also reacts to `android.media.VOLUME_CHANGED_ACTION` and master-mute changes.
Other broadcast/action names seen in the code (for completeness): `com.zhonghong.zuiserver.BROADCAST`, `com.zhonghong.LaunchAction`, `zhonghong.intent.action.START_UPDATE` / `END_UPDATE`, `com.polstar.navi.COORDINATE`, and the BT app's `com.zhonghong.bluetooth.call_last_number`. Packages referenced by the factory launcher but not installed on this unit: `com.zhonghong.avm` (surround camera), `com.zhonghong.carlife`, `com.zhonghong.power`, `com.zhonghong.phonelink`, `com.zhonghong.ledscreen`, `com.zhonghong.expandapps`.

---

### 3.9 Settings.System keys and system properties (readable by any app)

`Settings.System` (read; written by factory apps): `back_state` (`"true"` while reversing), `screen_brightness`, `screen_brightness_mode`, `navi_mix_value` (navigation-mix ducking value, default 10), `radio_rds_enbale`, `system_state_aux`, `bt_connected`, `key_bluetooth_state`, `key_signal_state`, `key_battery_state`, `key_type_car_level`, `time_12_24`, and the maintenance-reminder keys `CAR_ENGINE_OIL_CHANGE_TYPE`/`_REMAIN_TYPE`, `CAR_ENGINE_OIR_FILTER_CHANGE_TYPE`/`_REMAIN_TYPE`, `CAR_ENGINE_AIR_FILTER_CHANGE_TYPE`/`_REMAIN_TYPE`, `CAR_FUEL_FILTER_CHANGE_TYPE`/`_REMAIN_TYPE`. `Global`: `auto_time`, `auto_time_zone`, `auto_time_gps`.

System properties of interest: `sys.btcall` (BT call active), `sys.carlifevr`, `sys.stream.duckvalue` (duck level for streams), `zh.navi.pidlist`, `persist.sys.default.launcher`, `persist.sys.default_navi`, `persist.sys.default_voice_call`, `persist.sys.max_*`, `persist.phone.easyconnect`, `CarSettingKey` (= `0` on this unit), `ro.mcu.baudRate`, `ro.kernel.android.{gps,mcu,bt}`.
Read a property with `Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)`.

## 4. Other usable features found in the apps (beyond the obvious)

Each line: feature — where it comes from — how to use it.

**Vehicle data (read-only, via CAN service, section 3.1)**
- Vehicle speed from CAN (frame 1218) — the read path worked, but the test stayed at 0 throughout; speed units and moving-vehicle behaviour remain `[UNTESTED]`.
- Gear / shifter lever signals and transmission warnings (frame 1218): transmission warning lamp, OBD warning flag, clutch/transmission temperature warnings, winter mode, overall ratio.
- Two odometers (BCM 800 and ICM 1219).
- Full lamp/indicator state (frame 963 has per-lamp status incl. daylight, roof lamp, brake lamp, reversing lamp, corner lamps; frame 564 has the switch states, hazard).
- Wiper and window signals exist in the factory decoder, but the owner’s physical tests and the CarCheck run did not establish usable transitions; **do not add wiper/window controls or indicators to the launcher** unless new device evidence appears. Central-lock action pulses, remote lock/unlock signals, anti-theft state, key battery, PEPS/keyless status, steering lock status and key position remain raw/partly understood; do not turn them into user-facing features without separate calibration.
- Frame 722 exposes airbag-related raw fields, but ECU/crash/cut-off diagnostic meanings were not validated. The factory Car Settings UI has a passenger-airbag on/off setting/status; **retain access to that factory setting** rather than deriving a warning from unverified raw CAN bits.
- TPMS per wheel: pressure, temperature, status, leak, sensor error, sensor battery.
- Ambient temperature.
- Service-reminder values written to `Settings.System` (oil, oil filter, air filter, fuel filter): readable without binder.

**System / power / screen**
- ACC, power, illumination (headlight switch), reverse, AVM, "running" states with callbacks (Logic or SystemState broker) — use for automatic day/night, auto-wake and parking behaviour.
- Screen off on demand: broadcast `com.zhonghong.logic.close_screen`.
- System beep: broadcast `com.zhonghong.android.key` with `data=4608`.
- Brightness / backlight / TFT on-off / auto-brightness via MCU domain 2 (`brightness`, `backlight`, `TFT1`, `setautobrighton`) or `Settings.System screen_brightness`.
- Volume, mute, EQ (this unit exposes bass/mid/treble only, not six bands), bass/mid/treble, loudness, balance/fade, 3D audio, beep volume, navigation/TTS/GPS/BT/TBOX volumes, speed-dependent volume (`carspeed_volume`) via MCU domain 2. Reading `SYS_VOL` and `SYS_MUTE` also tells the current level.
- Custom audio streams: `STREAM_NAVI = 10`, per-stream maximum 30 (`persist.sys.max_*`).
- Audio source arbitration (Logic): query who has focus, request/release a source id.

**Media / radio / phone**
- Radio: tune, seek, scan, stop, area, RDS (station name, radio text, PTY, TP/TA/AF/EON), stereo/loc/APS flags, signal level (MCU domain 1/4).
- USB music: now playing + controls (service), full library (provider), launch the factory list.
- USB video: factory video Activity is available; external AIDL/PiP control is not established.
- Pictures viewer, AUX-in.
- Bluetooth: the factory stack internally supports connection/device/signal/battery, GOC A2DP music metadata, playback/transport commands, phone calls, contacts and call logs. However, the standard external `zhbt_service` is absent and the exported `btinfo`/phone providers returned no useful data in the probe. The EasyConnected A2DP-acquire → Logic media-key route is a promising path for steering-wheel music buttons, but needs an on-device test. Custom launcher metadata, artwork, touchscreen BT transport and phonebook/call-log UI are **not yet accessible** (Appendix H).
- Phone mirroring (`net.easyconn`, `persist.phone.easyconnect=true`) and CarLife (`ID_CARLIFE=12` source id; the CarLife app itself is not installed).

**Utilities already installed on the unit**
- ES File Explorer, X Browser, GPS Test, Android Settings, package installer — launchable only from a custom launcher; they are the unit's route to files, web and APK installation.
- Navigation apps: Target (`polnav6`), Neshan, Google Maps; `Settings.System navi_mix_value` = how loud music stays under navigation voice; `zh.navi.pidlist` = pids of navigation processes.
- Factory screens for car settings, EQ, system settings, update, factory test (`com.zh.test`, `com.zhonghong.uuidtool`).

**Hard keys / steering wheel**
- Logic recognises a media-key set including `85`, `86`, `87`, `88`, `126`, `127` (plus other system keys). If the active source has audio focus, `SourceHelper.handleMediaKey()` broadcasts `onMediaButtonPressed` to registered source callbacks with the current Logic source ID. The BT music helper uses source ID `3` and maps `85`=toggle, `86`=stop, `87`=next, `88`=previous, `126`=play, `127`=pause. The EasyConnected acquire path can select source 3 and register the callback. This is `[CODE]` only so far; physical steering-button behavior still needs a stationary-car test. Steering-wheel learning exists (`setWheelKeyStudy`, MCU domain 2) `[UNTESTED]`.
- Do **not** assume Android broadcasts/standard `MediaSession` events are generated for the steering buttons. The verified source route is the proprietary Logic callback described above; test before relying on Android `KEYCODE_MEDIA_*` in the launcher.

## 5. Verification status and open gaps (consolidated 2026-10-30; read before building)

**Verified by factory-code analysis (`[CODE]`):** AIDL descriptors and transaction numbers; CAN frame IDs, bit maps and factory decoder formulas; Logic keys/events/source IDs; MCU domains, JSON conventions and proxy methods; Bluetooth command constants; provider schemas/URIs; available factory activity actions and documented system-property/`Settings.System` keys. Code-derived formats are not the same as proven third-party runtime access.

**Confirmed from the real unit by `carcheck_20261027_224456.txt` (`[DEVICE]`):**
- Normal third-party app can bind `com.zhonghong.can/.CanService`, read CAN data, register its callback, and observe live `CAN-CHG` events (initial snapshot: 115 decoded signals).
- `_logic_service` getter calls and callback registration work; illumination and reverse-state events were observed.
- MCU `zhonghong.mcu` domain read-all works for domains 1, 2 and 3; selected keyed reads work. This is read access only, not proof that writes/actions work.
- `SystemStateService` binds, getter calls and callback registration work; volume, reverse and Bluetooth phone-state events were observed. `SystemStateService2` was only bound, not functionally exercised.
- CAN field meanings newly corroborated by physical actions include four side-door open/closed polarity, tested lamp-switch/output polarity, brake pedal, handbrake, driver belt, and a strongly supported shifter map (P=9, R=10, N=11, D=12; see Appendix G for details).
- USB music service binds and its data-change listener registers; media-library `audio` and `id3` providers each return 10 rows. A `TYPE_PHONE` overlay was successfully added.
- Bluetooth connection/call state can be observed via broker callbacks and `sys.btcall`; `sys.btcall` was true during the call and false after it ended.

**Still untested, unavailable or only partial:**
1. Vehicle speed while moving and its displayed unit; `KeyPositionStatus`; raw airbag/crash/cutoff meanings; trunk/bonnet and auxiliary switch meanings. The usable odometer value is now decoded in Appendix I, but must be sanity-checked against the cluster. Passenger belt, wiper and window signals are not part of the agreed launcher UI.
2. MCU writes (`sendInfo`), volume/brightness/EQ mutation, radio tuning/control, hard-key registration and key-event parcel decoding. Some keyed reads (`brightness`, `EQ1k`, `getfreq`) failed; do not infer all MCU keys work merely because domain read-all succeeds.
3. External Bluetooth control through `zhbt_service`: the lookup was null and the declared `BtCtrlManagerService` returns no Binder. `BtService` exists in source but is not declared in the BT manifest; direct calls/metadata from a normal app are not available through that route.
4. Bluetooth `btinfo` provider useful fields are null; `btcontacts` is empty. Phone provider contacts/call-log queries returned null cursors. The GOC stack internally parses ID3, but the app has no working external API for the launcher.
5. EasyConnected steering-key route: statically traced but **not physically tested**. A2DP-only state, callback lifecycle when source 3 is already active, release/disconnect handling, and the actual media-key event codes from this steering wheel still require a controlled test. `net.easyconn.apk` is missing from the current repository.
6. Launcher touchscreen Bluetooth play/pause/next/previous, title/artist/album display and album artwork remain unresolved; `BaseId3Info` has no artwork field.
7. USB music transport commands were not invoked; the probe logged only the length (not content) of returned title/artist. Video AIDL is not usable in the current service source (`onBind()` returns null); use the factory video Activity. Beep-broadcast audible effect and `SystemStateService2` generic provider methods are untested.
8. No server found for `IRadioAidlInterface`, `IBtMusicAidlInterface`, `KeyInterface`, `ScreenInterface`, `NaviInterface`, or `ExtraDeviceInterface`; no CAN command sender class; `zh_can_service` was not registered (direct CAN service binding works); AVM/CarLife/power/phonelink packages referenced by the factory launcher are absent.

**Known pitfalls**
- `targetSdkVersion 22` is deliberate so permissions (location, storage, system alert window, boot completed) are granted at install on a unit with no permission UI.
- The unit has 32-bit ABIs only; do not ship 64-bit-only native code.
- A foreground-less background process can be killed; the factory apps run as system uid and are not.
- Writing to the USB root is not allowed for a normal app; use `/storage/udiskN/Android/data/<pkg>/files`.
- Do not read raw serial ports, do not call the update or MCU-update services.
- The factory Logic/BT apps already provide their own call UI and reverse overlay; a custom incoming-call overlay may duplicate them. The call-state broker is not a full call-control API. Never cover the reverse-camera view.
- Do not open `/dev/goc_serial` or `/dev/ttyAMA*` from the launcher. The persistent factory Bluetooth process owns GOC serial I/O; concurrent reads/writes can break the built-in BT stack.

---

## Appendix A — Complete CAN signal map (auto-extracted from the factory decoder)

Format: `Signal  byte N  bit B  len L` — `N` is the data-byte index (add 3 for the array index), `B` the start bit inside the byte, `L` the bit length, little-endian across bytes. Frames: CarStatus = 564 and 577, CarSet = 963, 800, 722, 1219, 1218, TPMS = 1042 and 1043, AmbientTemp = 695. Semantics that are confirmed are listed in section 3.1; everything else is the raw signal name from the factory decoder.

```
## CarStatusInfo
  -- frame 564
   LH_Indicator_Switch_Status                   byte0  bit0  len1
   RH_Indicator_Switch_Status                   byte0  bit1  len1
   Position_Lamp_Switch_Status                  byte0  bit2  len1
   Low_Beam_Switch_Status                       byte0  bit3  len1
   High_Beam_Lamp_Switch_Status                 byte0  bit4  len1
   Front_Fog_Lamp_Switch_Status                 byte0  bit5  len1
   Rear_Fog_Lamp_Switch_Status                  byte0  bit6  len1
   Horn_Switch_Status                           byte0  bit7  len1
   Front_LH_Door_Switch_Status                  byte1  bit0  len1
   Front_RH_Door_Switch_Status                  byte1  bit1  len1
   Rear_LH_Door_Switch_Status                   byte1  bit2  len1
   Rear_RH_Door_Switch_Status                   byte1  bit3  len1
   Trunk_Lid_Switch_Status                      byte1  bit4  len1
   Bonnet_Switch_Status                         byte1  bit5  len1
   Wash_Pump_Switch_Status                      byte2  bit0  len1
   Wiper_INT_Switch_Status                      byte2  bit1  len1
   Wiper_Low_Speed_Switch_Status                byte2  bit2  len1
   Wiper_High_Speed_Switch_Status               byte2  bit3  len1
   Wiper_Auto_Switch_Status                     byte2  bit4  len1
   Rear_Wiper_Switch_Status                     byte2  bit5  len1
   Rear_Wash_Pump_Switch_Status                 byte2  bit6  len1
   Front_Wiper_Return_Switch_Status             byte2  bit7  len1
   Start_Switch_Status                          byte3  bit0  len2
   AC_Switch_Status                             byte3  bit2  len1
   Screen_Heater_Switch_Status                  byte4  bit0  len1
   Brake_Pedal_Switch_Status                    byte4  bit1  len1
   Hand_Brake_Switch_Status                     byte4  bit2  len1
   Reverse_Gear_Switch_Status                   byte4  bit3  len1
   Driver_Seat_Belt_Switch_Status               byte4  bit4  len1
   Passanger_Seat_Belt_Switch_Status            byte4  bit5  len1
   Passanger_Load_Cell_Switch_Status            byte4  bit6  len1
   Remote_Status                                byte5  bit0  len2
   Global_Lock_Unlock_Status                    byte5  bit2  len2
   FLH_Actuator_Switch_Status                   byte5  bit4  len1
   FRH_Actuator_Switch_Status                   byte5  bit5  len1
   Master_Lock_Switch_Status                    byte5  bit6  len1
   Anti_theft_Status                            byte5  bit7  len1
   LH_Window_Switch_Status                      byte6  bit0  len2
   RH_Window_Switch_Status                      byte6  bit2  len2
   Hazard_Switch_Status                         byte7  bit0  len1
   Trunk_Lid_Command_Switch_Status              byte7  bit1  len1
   Indicator_Lamps_Command                      byte7  bit2  len2
   Shock_Sensor_Command                         byte7  bit4  len1
   Resons_For_Lock_change_status                byte7  bit5  len2
  -- frame 577
   PowerDistributionStatus                      byte0  bit0  len2
   PKE_RKE_UnlockSignal                         byte0  bit2  len2
   PKE_RKE_LockSignal                           byte0  bit4  len2
   PKE_RKE_LuggageUnlockSignal                  byte0  bit6  len2
   BrakePedalStatus                             byte1  bit0  len2
   RemoteWindowUpSignal                         byte1  bit2  len1
   RemoteWindowDownSignal                       byte1  bit3  len1
   SearchCarSignal                              byte1  bit4  len1
   KeyInCarAlarmSignal                          byte1  bit5  len1
   KeyBatteryStatus                             byte1  bit6  len1
   RemoteAlarmCommand                           byte2  bit0  len4
   KeyPositionStatus                            byte2  bit4  len3
   Key_Position_Light_Command                   byte3  bit0  len3
   UnlockModeTransitionSignal                   byte3  bit3  len1
   UnallowStartupSignal                         byte3  bit4  len4
   PERequestButtonSignal                        byte4  bit0  len2
   Anti_theft_status_indication                 byte4  bit2  len2
   PEPS_Fail_Status                             byte4  bit4  len1
   PEPS_Anti_theft_Command                      byte4  bit5  len1
   IGSwitchSteerlockStatus                      byte4  bit6  len2
   IGSwitchSteerlockFaultBit                    byte5  bit0  len1
   ESCLLock_UnlockFailIndication                byte5  bit1  len2
   PEPS_AlarmStatus                             byte5  bit3  len2
   PEPS_ICMAlarm                                byte6  bit0  len4
   PEPS_ICMInd                                  byte6  bit4  len4
   ACCRelaySt                                   byte7  bit0  len1
   IG1RelaySt                                   byte7  bit1  len1
   IG2RelaySt                                   byte7  bit2  len1
   StartRelaySt                                 byte7  bit3  len1
## CarSetInfo
  -- frame 963
   Left_Indicator_Lamp_Status                   byte0  bit0  len2
   Right_Indicator_Lamp_Status                  byte0  bit2  len2
   Position_Lamp_Status                         byte0  bit4  len2
   Low_Beam_Status                              byte0  bit6  len2
   High_Beam_Status                             byte1  bit0  len2
   Front_Fog_Lamp_Status                        byte1  bit2  len2
   Rear_Fog_Lamp_Status                         byte1  bit4  len2
   Daylight_Status                              byte1  bit6  len2
   Roof_Lamp_Status                             byte2  bit0  len2
   Brake_Lamp_Status                            byte2  bit2  len2
   Reversing_Lamp_Status                        byte2  bit4  len2
   Left_Corner_Lamp_Status                      byte2  bit6  len2
   Right_Corner_Lamp_Status                     byte3  bit0  len2
   Vehicle_Type                                 byte3  bit2  len4
   Front_Wiper_Output_Status                    byte4  bit0  len3
   Rear_Wiper_Output_Status                     byte4  bit3  len2
   Wash_Output_Status                           byte4  bit5  len2
   Horn_Relay__Status                           byte5  bit0  len2
   Screen_Heater_Relay__Status                  byte5  bit2  len2
   Isolator_Relay__Status                       byte5  bit4  len2
   Veco_Relay__Status                           byte5  bit6  len2
   Door_Lock_Output__Status                     byte6  bit0  len2
   Trunk_Output__Status                         byte6  bit2  len2
   Buzzer_Output__Status                        byte6  bit4  len2
   Reaview_Mirror_Output__Status                byte6  bit6  len2
   Hazard_LED_Output_Status                     byte7  bit1  len1
   Master_Lock_LED_Output_Status                byte7  bit2  len1
   Child_Lock_LED_Indicator_Output_Status       byte7  bit3  len1
   Anti_theft_Indicator_Output_Status           byte1  bit4  len1
   Start_Stop_Amber_LED_Output_Status           byte1  bit5  len1
   Start_Stop_Green_LED_Output_Status           byte1  bit6  len1
  -- frame 800
   ODO_calculated_By_BCM                        byte0  bit0  len16
  -- frame 722
   Checksum_of_message_Airbag_1                 byte0  bit0  len8
   The_crash_intensity_describes_whether_there_is_a_crash byte1  bit0  len3
   Driver_seatbelt_lock_recognition             byte2  bit0  len2
   Passenger_seatbelt_lock_recognition          byte2  bit2  len2
   Airbag_counter_for_live_check                byte4  bit4  len4
   Error_flag_of_the_airbag_ECU                 byte5  bit1  len1
   Passenger_airbag_cut_off_status              byte5  bit2  len1
  -- frame 1219
   ODO_calculated_By_ICM                        byte0  bit0  len16
  -- frame 1218
   Torque_Reserve_Request                       byte0  bit0  len8
   Transmission_EOL_Status                      byte1  bit0  len8
   Fuel_Cut_Off_Assist_Flag                     byte1  bit1  len1
   Shifter_Lever_Signals                        byte1  bit2  len4
   Winter_Mode_Status                           byte1  bit6  len1
   Transmission_Warning_Lamp                    byte2  bit0  len1
   OBD_Warning_Flag_Request                     byte2  bit1  len1
   Clutch_Temperature_Warning                   byte2  bit2  len1
   Transmision_Temperature_Warning              byte2  bit3  len1
   Overal_Ratio                                 byte3  bit0  len16
   Vehicle_Speed                                byte5  bit0  len8
## TpmsInfo
  -- frame 1042
   FLTyrePressureValue                          byte0  bit0  len8
   FRTyrePressureValue                          byte1  bit0  len8
   RLTyrePressureValue                          byte2  bit0  len8
   RRTyrePressureValue                          byte3  bit0  len8
   FLTyreTemperatureValue                       byte4  bit0  len8
   FRTyreTemperatureValue                       byte5  bit0  len8
   RLtyreTemperatureValue                       byte6  bit0  len8
   RRTyreTemperatureValue                       byte7  bit0  len8
  -- frame 1043
   FLtyrePressureStatus                         byte0  bit0  len3
   FLtyreTemperatureStauts                      byte0  bit3  len3
   FLTPMSError                                  byte0  bit6  len1
   FLAirLeakError                               byte0  bit7  len1
   FRTyrePressureStatus                         byte1  bit0  len3
   FRTyreTemperatureStauts                      byte1  bit3  len3
   FRTPMSError                                  byte1  bit6  len1
   FRAirLeakError                               byte1  bit7  len1
   RLTyrePressureStatus                         byte2  bit0  len3
   RLtyreTemperatureStauts                      byte2  bit3  len3
   RLTPMSError                                  byte2  bit6  len1
   RLAirLeakError                               byte2  bit7  len1
   RRtyrePressureStatus                         byte3  bit0  len3
   RRTyreTemperatureStauts                      byte3  bit3  len3
   RRTPMSError                                  byte3  bit6  len1
   RRAirLeakError                               byte3  bit7  len1
   FLTyreSelfLocalizationStatus                 byte4  bit0  len2
   FRTyreSelfLocalizationStatus                 byte4  bit2  len2
   RLTyreSelfLocalizationStatus                 byte4  bit4  len2
   RRTyreSelfLocalizationStatus                 byte4  bit6  len2
   PEPS_TPMSSystemConfiguration                 byte5  bit0  len2
   FLSensorLowBattery                           byte5  bit2  len1
   FRSensorLowBattery                           byte5  bit3  len1
   RLSensorLowBattery                           byte5  bit4  len1
   RRSensorLowBattery                           byte5  bit5  len1
   is413CanValid                                byte8  bit0  len8
## AmbientTempInfo
  -- frame 695
   AmbientTemp                                  byte0  bit0  len8
```
## Appendix B — MCU proxy API (domain / command per factory method)

Each line: `method(args) -> manager call(domain, cmd, key…)`. `sendJson(d, ...)` = `sendInfo(d, 1, {json})`; `getJson*(d, 1, "KEY", default)` = keyed read; `obtainAllJsonMsg(d,1)` = read-all with `{"all":0}`; `sendData(d, bytes)` = raw binary payload to domain `d`.

```
## SystemProxy
  getSystemInfoJson() -> obtainAllJsonMsg(3, 1)
  getSystemInfo() -> obtainAllParcel(3, 1, out)
  sendCmd(byte[] data) -> sendData(3, data)
  setMcuSource(int source) -> sendJson(3, data)
  getMcuSource() -> getJsonInt(3, 1, "MCU_SOURCE", 0)
  sendPowerKeyToMcu() -> sendJson(3, data)
  sendResetKeyToMcu() -> sendJson(3, data)
  sendUpdateStateToMcu(int state) -> sendJson(3, data)
  reqMcuPoweron() -> sendJson(3, data)
  sendResetCmdToMcu(int cmd) -> sendJson(3, data)
  notifyUnrealPoweroffToMcu(int cmd) -> sendJson(3, data)
  notifyAnimationToMcu(int cmd) -> sendJson(3, data)
  notifyNaviVoiceToMcu(int cmd) -> sendJson(3, data)
  notifyMcuMute1S() -> sendJson(3, data)
  sendBeepToMcu() -> sendJson(3, data)
  setMcuStateByBit(int state) -> sendJson(3, data)
  setMcuState(int state) -> sendJson(3, data)
  clearMcuStateByBit(int state) -> sendJson(3, data)
  getMcuState() -> getJsonInt(3, 1, "MCU_STATE", 0)
  getBPowerOn() -> getJsonBoolean(3, 1, "BPOWER_ON", false)
  setBPowerOnFalse() -> sendJson(3, data)
  getViceDriverHeadPhone() -> getJsonBoolean(3, 1, "VICEDIRVER_HEADPHONE", false)
  getBrightTurner() -> getJsonInt(3, 1, "BRIGHT_TURNER", 100)
  getReservedData() -> getPracel(3, 3, source)
  isParking() -> getJsonBoolean(3, 1, "BACK_SIGNAL", false)
  isSysReady() -> getJsonBoolean(3, 1, "SYSREADY", true)
  isAccOn() -> getJsonBoolean(3, 1, "ACC_STATE", true)
  getShowTouchDialogState() -> getJsonInt(3, 1, "NEED_SHOWDIALOG", 0)
  isPowerOnState() -> getJsonBoolean(3, 1, "POWER_STATE", false)
  getMcuTime() -> getJsonLong(3, 1, "MCU_TIME", 0L)
  getSysTime() -> getJsonLong(3, 1, "SYS_TIME", 0L)
  isIllState() -> getJsonBoolean(3, 1, "ILL_STATE", false)
## SettingsProxy
  sendCmd(byte[] data) -> sendData(2, data)
  getOfflineConfigInfo() -> obtainConfigInfo(8, 0)
  getSetupInfo() -> obtainAllJsonMsg(2, 1)
  getSettingsInfo() -> obtainAllParcel(2, 1, out)
  setBrightness(int brightness) -> sendJson(2, jsondata)
  getBrightness() -> getJsonInt(2, 1, "brightness", 0)
  setRrsAutoLevel(int data) -> sendJson(2, jsondata)
  getRrsAutoLevel() -> getJsonInt(2, 1, "RRS_AUTO_LEVEL", 0)
  getRrsRturnEn() -> getJsonInt(2, 1, "RRS_RTURN_EN", 0)
  setRrsRturnEn(int data) -> sendJson(2, jsondata)
  getAutoRrsTouchReq() -> getJsonInt(2, 1, "RRS_AUTO_REQ", 0)
  is3DAudioEnabled() -> getJsonBoolean(2, 1, "3D_AUDIO_SW", false)
  enable3DAudio(boolean z) -> sendJson(2, jSONObject.toString().getBytes())
  isBeepEnabled() -> getJsonBoolean(2, 1, "SYS_BEEP", false)
  enableBeep(boolean z) -> sendJson(2, jSONObject.toString().getBytes())
  getBeepVol() -> getJsonInt(2, 1, "SYS_BEEPVOL", -1)
  setBeepVol(byte vol) -> sendJson(2, jsondata)
  setVol(byte vol) -> sendJson(2, jsondata)
  getVol() -> getJsonInt(2, 1, "SYS_VOL", -1)
  setAudio40(byte data) -> sendJson(2, jsondata)
  getAudio40() -> getJsonInt(2, 1, "EQ40", 0)
  setAudio80(byte data) -> sendJson(2, jsondata)
  getAudio80() -> getJsonInt(2, 1, "EQ80", 0)
  setAudio500(byte data) -> sendJson(2, jsondata)
  getAudio500() -> getJsonInt(2, 1, "EQ500", 0)
  setAudio1k(byte data) -> sendJson(2, jsondata)
  getAudio1k() -> getJsonInt(2, 1, "EQ1k", 0)
  setAudio5k(byte data) -> sendJson(2, jsondata)
  getAudio5k() -> getJsonInt(2, 1, "EQ5k", 0)
  setAudio16k(byte data) -> sendJson(2, jsondata)
  getAudio16k() -> getJsonInt(2, 1, "EQ16k", 0)
  setTreb(byte data) -> sendJson(2, jsondata)
  getTreb() -> getJsonInt(2, 1, "treb", 0)
  setMid(byte data) -> sendJson(2, jsondata)
  getMid() -> getJsonInt(2, 1, "SYS_MID", 0)
  setBass(byte data) -> sendJson(2, jsondata)
  getBass() -> getJsonInt(2, 1, "bass", 0)
  enableLoud(boolean z) -> sendJson(2, jSONObject.toString().getBytes())
  isLoudEnabled() -> getJsonBoolean(2, 1, "SYS_LOUD", false)
  setFade(byte data) -> sendJson(2, jsondata)
  getFade() -> getJsonInt(2, 1, "audiofade", 0)
  setBal(byte data) -> sendJson(2, jsondata)
  getBal() -> getJsonInt(2, 1, "audiobal", 0)
  reqMcuVerInside() -> sendJson(2, jsondata)
  reqCanVer() -> sendJson(2, jsondata)
  setMute(boolean z) -> sendJson(2, jSONObject.toString().getBytes())
  isMute() -> getJsonBoolean(2, 1, "SYS_MUTE", false)
  setBtVol(byte data) -> sendJson(2, jsondata)
  getBtVol() -> getJsonInt(2, 1, "SYS_BTVOL", 0)
  setFmLimiter(byte data) -> sendJson(2, jsondata)
  getFmLimiter() -> getJsonInt(2, 1, "FM_LIMITER", 0)
  setAmLimiter(byte data) -> sendJson(2, jsondata)
  getAmLimiter() -> getJsonInt(2, 1, "AM_LIMITER", 0)
  setSpeedAdjustVolume(byte data) -> sendJson(2, jsondata)
  getSpeedAdjustVolume() -> getJsonInt(2, 1, "carspeed_volume", 0)
  setTboxVol(byte data) -> sendJson(2, jsondata)
  getTboxVol() -> getJsonInt(2, 1, "SYS_TBOXVOL", -1)
  getWelcomeState() -> getJsonInt(2, 1, "WELCOME_REQ", -1)
  forceMcuMute(byte data) -> sendJson(2, jsondata)
  setTTSVol(byte data) -> sendJson(2, jsondata)
  getTTSVol() -> getJsonInt(2, 1, "SYS_TTSVOL", -1)
  setGPSVol(byte data) -> sendJson(2, jsondata)
  getGpsVol() -> getJsonInt(2, 1, "SYS_GPSVOL", -1)
  setDefVol(byte data) -> sendJson(2, jsondata)
  getDefVol() -> getJsonInt(2, 1, "defvol", -1)
  setSysFlag(byte data) -> sendJson(2, jsondata)
  getSysFlag() -> getJsonInt(2, 1, "SYS_FLAG", -1)
  setSysFlag2(byte data) -> sendJson(2, jsondata)
  getSysFlag2() -> getJsonInt(2, 1, "SYS_SYSFLAG2", -1)
  setAudioMode(byte data) -> sendJson(2, jsondata)
  getAudioMode() -> getJsonInt(2, 1, "SYS_AUDIOMODE", 0)
  setTimeFormat24(boolean is24hFormat) -> sendJson(2, jsondata)
  isTimeFormat24() -> getJsonBoolean(2, 1, "SYS_TIME12H", false)
  getCarDoorUnlockState() -> getJsonInt(2, 1, "UNLOCK_STATE", -1)
  setTftOn(boolean val) -> sendJson(2, jsondata)
  isTftOn() -> getJsonBoolean(2, 1, "TFT1", true)
  setTftOn2(boolean val) -> sendJson(2, jsondata)
  isTftOn2() -> getJsonBoolean(2, 1, "TFT2", false)
  setSave1(byte data) -> sendJson(2, jsondata)
  getSave1() -> getJsonInt(2, 1, "SYS_ARMSAVE1", 0)
  setSave2(byte data) -> sendJson(2, jsondata)
  getSave2() -> getJsonInt(2, 1, "SYS_ARMSAVE2", 0)
  setSave3(byte data) -> sendJson(2, jsondata)
  getSave3() -> getJsonInt(2, 1, "SYS_ARMSAVE3", 0)
  setAutoBrightOn(boolean open) -> sendJson(2, jsondata)
  getAutoBrightOn() -> getJsonBoolean(2, 1, "setautobrighton", true)
  setBacklight(byte data) -> sendJson(2, jsondata)
  getBacklight() -> getJsonInt(2, 1, "backlight", 5)
  setViceBacklight(byte data) -> sendJson(2, jsondata)
  getViceBacklight() -> getJsonInt(2, 1, "vicebacklight", 5)
  setLanguageToMcu(byte data) -> sendJson(2, jsondata)
  getSysLanguage() -> getJsonInt(2, 1, "LANG_SEL", 1)
  setProductInfoToMcu(byte data) -> sendJson(2, jsondata)
  getProductInfo() -> getJsonInt(2, 1, "PRODUCT_INFO", 0)
  setFactoryVerCtrl(byte data) -> sendJson(2, jsondata)
  getFactoryVerCtrl() -> getJsonInt(2, 1, "CAN_COMPANY", 0)
  setWheelKeyStudy(int keyVal) -> sendJson(2, jsondata)
## McuHardKeyProxy
## SystemKeyProxy
## TouchProxy
## CustomerProxy
## ATestProxy
## CanProxy
## RadioProxy
  getRadioJson() -> obtainAllJsonMsg(1, 1)
  getRadioInfo() -> obtainAllParcel(1, 1, out)
  sendCmd(byte[] data) -> sendData(1, data)
  setFreq(int band, int freq) -> sendJson(1, data)
  getFreq() -> getJsonInt(1, 1, "getfreq", 0)
  setScan() -> sendJson(1, data)
  getScan() -> getJsonInt(1, 1, "scan", 0)
  setLoc() -> sendJson(1, data)
  getLoc() -> getJsonBoolean(1, 1, "loc", false)
  setSt() -> sendJson(1, data)
  getSt() -> getJsonBoolean(1, 1, "st", false)
  getStInd() -> getJsonBoolean(1, 1, "stind", false)
  setSearch() -> sendJson(1, data)
  getSearch() -> getJsonBoolean(1, 1, "aps", false)
  getArea() -> getJsonInt(1, 1, "RADIO_AREA", 0)
  setSearchRight() -> sendJson(4, data)
  setSearchLeft() -> sendJson(4, data)
  isLRSearch() -> getJsonBoolean(1, 1, "RADIO_SREACH_STATE", false)
  stopAllSearchMode() -> sendJson(1, data)
  setPlayState(int state) -> sendJson(1, data)
  getPlayState() -> getJsonInt(1, 1, "RADIO_PLAYSTATE", 0)
  setAuxPlayState(int state) -> sendJson(1, data)
  getAuxPlayState() -> getJsonInt(1, 1, "AUX_PLAYSTATE", 0)
  setClickTA() -> sendJson(1, data)
  setClickAF() -> sendJson(1, data)
  setRdsOnOff(boolean isOn) -> sendJson(1, data)
  setRdsPty(int type) -> sendJson(1, data)
  setFactoryRadioArea(int area) -> sendJson(1, jsondata)

```
## Appendix C — Constants

### C.1 Bluetooth (`ZHConstant`)

```
int ANSWER_MODE_INCOMING = 0;
int ANSWER_MODE_KEEPCALL = 1;
int ANSWER_MODE_TERMINATECALL = 2;
int BOND_STATE_BONDED = 2;
int BOND_STATE_BONDING = 1;
int BOND_STATE_NONE = 0;
int BT_UPDATE_FAIL = 4;
int BT_UPDATE_RUN = 2;
int BT_UPDATE_START = 1;
int BT_UPDATE_SUCCESS = 3;
int CALLLOG_TYPE_DIALED = 7;
int CALLLOG_TYPE_MISSED = 5;
int CALLLOG_TYPE_RECEIVED = 6;
int CALL_STATE_ALERTING = 3;
int CALL_STATE_HELD_BY_RESPONSE_AND_HOLD = 6;
int CALL_STATE_HOLD = 1;
int CALL_STATE_INCOMING = 4;
int CALL_STATE_OUTGOING = 2;
int CALL_STATE_TALKING = 0;
int CALL_STATE_TERMINATED = 7;
int CALL_STATE_WAITING = 5;
int CMD_ANSWER = 29;
int CMD_CONNECT = 7;
int CMD_CONNECT_A2DP = 13;
int CMD_CONNECT_HFP = 14;
int CMD_DIAL = 34;
int CMD_DIAL_KEY_NUM = 35;
int CMD_DISCONNECT = 8;
int CMD_DISCONNECT_A2DP = 16;
int CMD_DISCONNECT_HFP = 15;
int CMD_ENABLE = 1;
int CMD_ENABLE_AUTO_CONNECT = 27;
int CMD_FAST_BACKWARD = 40;
int CMD_FAST_FORWARD = 39;
int CMD_HANGUP = 30;
int CMD_NEXT = 17;
int CMD_PAIR = 9;
int CMD_PAUSE = 20;
int CMD_PLAY = 19;
int CMD_PREV = 18;
int CMD_REGISTER_PLAY_CALLBACK = 32;
int CMD_REQUEST_ID3INFO = 24;
int CMD_REQUEST_PAIRED_DEVICE = 6;
int CMD_REQUEST_PLAY_STATE = 25;
int CMD_SET_AUTO_ANSWER = 41;
int CMD_SET_AUTO_CONNECT = 28;
int CMD_SET_DISCOVERY = 5;
int CMD_SET_MIC_MUTE = 37;
int CMD_SET_MIC_TO_CAR = 36;
int CMD_SET_MUSIC_MUTE = 21;
int CMD_SET_NAME = 2;
int CMD_SET_PASSWORD = 42;
int CMD_SET_PHONE_MUTE = 38;
int CMD_SET_SOUND = 23;
int CMD_START_BT_UPDATE = 12;
int CMD_START_SCAN = 3;
int CMD_STOP = 26;
int CMD_STOP_SCAN = 4;
int CMD_UNPAIR = 10;
int CMD_UNPAIR_ALL = 11;
int CMD_UNREGISTER_PLAY_CALLBACK = 33;
int CMD_UPDOWN_SOUND = 22;
int CONTACT_STORAGE_CALLLOG = 2;
int CONTACT_STORAGE_PHONE = 1;
int CONTACT_STORAGE_SIM = 0;
int CONTACT_TYPE_FAX = 1;
int CONTACT_TYPE_HOME = 2;
int CONTACT_TYPE_MSG = 3;
int CONTACT_TYPE_NULL = 4;
int CONTACT_TYPE_PAGER = 5;
int CONTACT_TYPE_PHONE = 0;
int CONTACT_TYPE_PREF = 6;
int CONTACT_TYPE_VOICE = 7;
int CONTACT_TYPE_WORK = 8;
int DISCOVER_STATE_CONNECTABLE = 1;
int DISCOVER_STATE_CONNECTABLE_DISCOVERABLE = 2;
int DISCOVER_STATE_NONE = 0;
int DOMAIN_ID_CALLLOG = 5;
int DOMAIN_ID_CONTACT = 4;
int DOMAIN_ID_MUSIC = 2;
int DOMAIN_ID_PHONE = 3;
int DOMAIN_ID_SETTINGS = 1;
int DOWNLOAD_FAILED_STATE = 2;
int DOWNLOAD_OTHER_FINISH_STATE = 4;
int DOWNLOAD_START_STATE = 0;
int DOWNLOAD_SUCCESS_STATE = 1;
int DOWNLOAD_USER_REJECT_STATE = 3;
String EVENT_ON_A2DP_CONNECT_STATE_CHANGED = "onA2dpConnectStateChanged";
String EVENT_ON_A2DP_STREAM_CHANGED = "onA2dpStreamChanged";
String EVENT_ON_ADDRESS_CHANGED = "onAddressChanged";
String EVENT_ON_AUDIO_SESSIONID_CHANGED = "onAudioSessionIDChanged";
String EVENT_ON_BATTERY_CHANGED = "onBatteryChanged";
String EVENT_ON_BTUPDATE_STATE = "onBtUpdateState";
String EVENT_ON_CALLLOG_DATA_CHANGED = "onCallLogDataChanged";
String EVENT_ON_CALL_STATE_CHANGED = "onCallStateChanged";
String EVENT_ON_CONNECT_STATE_CHANGED = "onConnectStateChanged";
String EVENT_ON_DEVICE_BOND_STATE_CHANGED = "onDeviceBondStateChanged";
String EVENT_ON_DEVICE_CHANGED = "onDeviceChanged";
String EVENT_ON_DEVICE_OUTOF_RANGE = "onDeviceOutOfRange";
String EVENT_ON_DISCOVERABLE_STATE_CHANGED = "onDiscoverableStateChanged";
String EVENT_ON_DONWLOAD_STATE_CHANGED = "onDonwloadStateChanged";
String EVENT_ON_DOWNLOAD_FINISH = "onDownloadFinish";
String EVENT_ON_ENABLE_STATE_CHANGED = "onEnableStateChanged";
String EVENT_ON_FOUND_REMOTE_PHONE_INFO = "onFoundRemotePhoneInfo";
String EVENT_ON_ID3_INFO_CHANGED = "onId3InfoChanged";
String EVENT_ON_MIC_MUTE_STATE_CHANGED = "onMicMuteStateChanged";
String EVENT_ON_NAME_CHANGED = "onNameChanged";
String EVENT_ON_PASSWORD_CHANGED = "onPasswordChanged";
String EVENT_ON_PLAY_DURATION_CHANGED = "onPlayDurationChanged";
String EVENT_ON_PLAY_POSITION_CHANGED = "onPlayPositionChanged";
String EVENT_ON_PLAY_STATE_CHANGED = "onPlayStateChanged";
String EVENT_ON_READYOK = "onReadyOk";
String EVENT_ON_SCAN_STATE_CHANGED = "onScanStateChanged";
String EVENT_ON_SIGNAL_STRENGTH_CHANGED = "onSignalStrengthChanged";
String EVENT_ON_VERSION_CHANGED = "onVersionChanged";
String EVENT_ON_VOICE_STATE_CHANGED = "onVoiceStateChanged";
String GET_A2DP_STATE = "getA2dpState";
String GET_ADDRESS = "getAddress";
String GET_ALL_CALLLOGS = "getALLCalllogs";
String GET_AUDIO_SESSION_ID = "getAudioSessionId";
String GET_CALLS = "getCalls";
String GET_CALL_STATE = "getCallState";
String GET_CONNECTD_EVICE = "getConnectDevice";
String GET_CONNECT_STATE = "getConnectState";
String GET_CUR_BATTERY_VALE = "getCurBatteryVale";
String GET_CUR_POSITION = "getCurPosition";
String GET_CUR_SIGNAL_STRENGTH = "getCurSignalStrength";
String GET_DEVICES = "getDevices";
String GET_DURATION = "getDuration";
String GET_ENABLE_STATE = "getEnableState";
String GET_ID3_INFO = "getId3Info";
String GET_MAX_BATTERY_VALE = "getMaxBatteryVale";
String GET_MAX_SIGNAL_STRENGTH = "getMaxSignalStrength";
String GET_MIN_BATTERY_VALE = "getMinBatteryVale";
String GET_MIN_SIGNAL_STRENGTH = "getMinSignalStrength";
String GET_NAME = "getName";
String GET_PASSWORD = "getPassword";
String GET_PLAY_STATE = "getPlayState";
String GET_REMOTE_A2DP_ADDESS = "getRemoteA2dpAddess";
String GET_REMOTE_ADDRESS = "getRemoteAddress";
String GET_REMOTE_PHONE_NUMBER = "getRemotePhoneNumber";
String GET_REMOTE_PHONE_PERATOR = "getRemotePhonOperator";
String GET_VERSION = "getVersion";
int HANGUP_MODE_CURCALL = 1;
int HANGUP_MODE_INCOMING = 0;
int HANGUP_MODE_MULTIPLE = 2;
String IS_3CALLING = "is3Calling";
String IS_A2DP_CONNECTED = "isA2dpConnected";
String IS_A2DP_STREAM = "isA2dpStream";
String IS_AUTO_ANSWER = "isAutoAnswer";
String IS_CALLLOG_DOWNLOAD = "isCalllogDownloading";
String IS_CONNECTED = "isConnected";
String IS_COVERABLE = "isCoverable";
String IS_ENABLED = "isEnabled";
String IS_ENABLE_AUTO_CONNECT = "isEnableAutoConnect";
String IS_MIC_MUTE = "isMicMute";
String IS_OPEN_AUTO_CONNECT = "isOpenAutoConnect";
String IS_PLAYING = "isPlaying";
String IS_SCANNING = "isScanning";
String IS_TAKING = "isTaking";
String IS_VOICE_IN_CAR = "isVoiceInCar";
String KEY_EVENT = "event";
String KEY_VALUE0 = "value0";
String KEY_VALUE1 = "value1";
String KEY_VALUE2 = "value2";
String KEY_VALUE3 = "value3";
int PLAY_STATE_ERROR = 5;
int PLAY_STATE_FWD_SEEK = 3;
int PLAY_STATE_PAUSED = 1;
int PLAY_STATE_PLAYING = 0;
int PLAY_STATE_REW_SEEK = 4;
int PLAY_STATE_STOPPED = 2;
int PROFILE_A2DP = 2;
int PROFILE_AVRCP = 4;
int PROFILE_AVRCP_13 = 8;
int PROFILE_AVRCP_14 = 16;
int PROFILE_BLUETOOTH = 0;
int PROFILE_DUN = 2048;
int PROFILE_FTP = 4096;
int PROFILE_HFP = 1;
int PROFILE_HID = 256;
int PROFILE_IAP = 8192;
int PROFILE_MAP = 64;
int PROFILE_NAP = 1024;
int PROFILE_PANU = 512;
int PROFILE_PBAP = 32;
int PROFILE_SPP = 128;
String QUERY_NAME_BY_NUMBER = "queryNameByNumber";
int STATE_A2DP_CONNECTED = 2;
int STATE_A2DP_CONNECTING = 1;
int STATE_A2DP_DISCONNECTTING = 3;
int STATE_A2DP_NOT_INIT = 5;
int STATE_A2DP_READY = 0;
int STATE_A2DP_STREAM = 4;
int STATE_CONNECTED = 2;
int STATE_CONNECTING = 1;
int STATE_DIS_CONNECTING = 3;
int STATE_DOWNLOAD_DOWNLOADING = 2;
int STATE_DOWNLOAD_NOT_INIT = 0;
int STATE_DOWNLOAD_READY = 1;
int STATE_OFF = 10;
int STATE_ON = 12;
int STATE_READY = 0;
int STATE_TURNING_OFF = 13;
int STATE_TURNING_ON = 11;
int TYPE_GOC_JAVA = 1;
int TYPE_IVT_JAVA = 2;
int TYPE_NFORE_AIDL = 0;
```
### C.2 Logic (`LogicConstant`)
```
String CMD_CLOSE_SOURCE_ID = "closeSourceId";
String CMD_RELEASE_SOURCE_ID = "releaseSourceId";
String CMD_SET_AUTO_BRIGHT = "setAutoBright";
String CMD_SET_BRIGHT = "setBright";
String CMD_SET_SOURCE_ID = "setSourceId";
String CMD_SET_VOLUME = "setVolume";
String CMD_SET_VOLUME_CHANGE = "setVolumeChange";
int DOMAIN_KEY = 3;
int DOMAIN_LOGIC_STATE = 1;
int DOMAIN_SETTING = 4;
int DOMAIN_SOURCE = 2;
String EVENT_ON_ACC_STATE_CHANGED = "onAccStateChanged";
String EVENT_ON_AUX_STATE_CHANGED = "onAuxStateChanged";
String EVENT_ON_AVM_STATE_CHANGED = "onAvmStateChanged";
String EVENT_ON_BACK_STATE_CHANGED = "onBackStateChanged";
String EVENT_ON_BRIGHT_MODE_CHANGED = "onBrightModeChanged";
String EVENT_ON_BRIGH_CHANGED = "onBrightChanged";
String EVENT_ON_FOUCS_STATE_CHANGED = "onFoucsStateChanged";
String EVENT_ON_ILL_STATE_CHANGED = "onIllStateChanged";
String EVENT_ON_KEY_EVENT = "onKeyEvent";
String EVENT_ON_MEDIA_BUTTON_PRESSED = "onMediaButtonPressed";
String EVENT_ON_POWER_STATE_CHANGED = "onPowerStateChanged";
String EVENT_ON_RUNNING_STATE_CHANGED = "onRunningStateChanged";
String EVENT_ON_SOURCE_CHANGED = "onSourceChanged";
String EVENT_ON_TFT_STATE_CHANGED = "onTftStateChanged";
String EVENT_ON_THIRD_SOURCE_CHANDED = "onThirdSourceChanged";
String EVENT_RECEIVER_FOUCS_STATE_CHANGED = "receiverFoucsStateChanged";
String GET_AVM_STATE = "getAvmState";
String GET_BRIGHT = "getBright";
String GET_FOCUS_STATE = "getFocusState";
String GET_FOCUS_STATE_BY_ID = "getFocusStateById";
String GET_MAX_BRIGHT = "getMaxBright";
String GET_MIN_BRIGHT = "getMinBright";
String GET_SOURCE_ID = "getSourceId";
String GET_STREAM_TYPE = "getStreamType";
String GET_THIRD_SOURCE_ID = "getThirdSourceId";
String GET_VOLUME = "getVolume";
String HAS_FOCUS_BY_ID = "hasFocusById";
int ID_AUX = 11;
int ID_BT_MUSIC = 3;
int ID_BT_PHONE = 13;
int ID_CARLIFE = 12;
int ID_MUSIC = 1;
int ID_NEWS = 6;
int ID_NULL = 4096;
int ID_OFF_ARM = 4097;
int ID_OFF_MCU = 4098;
int ID_ONLINE_MUSIC = 4;
int ID_ONLINE_PROGRAM = 7;
int ID_ONLINE_RADIO = 5;
int ID_ONLINE_VIDEO = 8;
int ID_OTHER_APP = 4099;
int ID_RADIO = 0;
int ID_SD_MUSIC = 9;
int ID_SD_VIDEO = 10;
int ID_THIRD_AUDIO_APP = 15;
int ID_VIDEO = 2;
int ID_VOICE = 14;
String IS_ACC_ON = "isAccOn";
String IS_AUTO_BRIGHT = "isAutoBright";
String IS_AUX_STATE = "isAuxState";
String IS_ILL_ON = "isIllon";
String IS_IN_BACK_STATE = "isInBackState";
String IS_POWER_ON = "isPowerOn";
String IS_RUNNING = "isRunning";
String IS_TFT_ON = "isTftOn";
String LOGIC_SERVICE_NAME = "_logic_service";
```
## Appendix D — Every AIDL interface found (descriptor, transaction code, method)

Codes are the generated `TRANSACTION_*` numbers (first-declared = 1). `?` = code not parsed (see the descriptor section above for IMcuService).

Bluetooth backend source audit: the active `BtApp` configuration selects GOC (`BtSDK.Config(1)` → `GocBluetoothAdapter` / `GocJavaController`). The APK also contains 31 NForetek AIDL interfaces (`com.nforetek.bt.aidl.*`, e.g. `UiCommand` and callbacks), but they belong to an alternative, unselected backend in this startup path. Do not build the integration around NForetek unless a different firmware/configuration is demonstrated. See Appendix H.

```
## android.zhonghong.IDataChangedListener   (found in: zhset)
  2   void notify(int i, int i2, int i3, Parcel parcel)
## android.zhonghong.IMcuService   (found in: zhset)
  ?   int getTest()
  ?   boolean obtainInfo(int i, int i2, Parcel parcel, Parcel parcel2)
  ?   int registDataChangedListener(int i, IDataChangedListener iDataChangedListener)
  ?   boolean sendInfo(int i, int i2, Parcel parcel)
  ?   int unregistDataChangedListener(int i, IDataChangedListener iDataChangedListener)
## com.logic.common.aidl.ILogicCallback   (found in: zhset)
  1   void onEventChanged(String str, String str2, String str3)
## com.logic.common.aidl.ILogicInterface   (found in: zhset)
  4   String getValue(String str, String str2)
  1   void registerLogicCallback(int i, ILogicCallback iLogicCallback)
  3   void setCmd(String str, String str2, String str3)
  2   void unregisterLogicCallback(int i, ILogicCallback iLogicCallback)
## com.zhonghong.aidlib.extraaudiodevice.ExtraDeviceFocusListener   (found in: zhset)
  1   void onAudioFocusChange(int i)
## com.zhonghong.aidlib.extraaudiodevice.ExtraDeviceInterface   (found in: zhset)
  2   int abandonSecondaryAudioFocus(ExtraDeviceFocusListener extraDeviceFocusListener)
  1   int requestSecondaryAudioFocus(ExtraDeviceFocusListener extraDeviceFocusListener, int i, int i2)
## com.zhonghong.aidlib.key.KeyCallBack   (found in: zhset)
  1   void notifyTouchKey(String str, int i)
## com.zhonghong.aidlib.key.KeyInterface   (found in: zhset)
  1   void registerCallBack(KeyCallBack keyCallBack)
  3   void sendKey(String str, int i)
  2   void unregisterCallBack(KeyCallBack keyCallBack)
## com.zhonghong.aidlib.multiscreen.ScreenCallBack   (found in: zhset)
  2   void notifyLockScreenState(boolean z)
  3   void notifyScreenPosChange(String str, String str2)
  1   void notifyViceScreenState(boolean z)
## com.zhonghong.aidlib.multiscreen.ScreenInterface   (found in: zhset)
  10  void broadcastScreenPosChange(String str, String str2)
  2   int getCurrentScreenPosition(String str)
  11  String getScreenApplication(int i)
  3   boolean isLockScreen()
  1   boolean isViceScreenOn()
  6   void registerCallBack(ScreenCallBack screenCallBack)
  8   void registerProvider(ScreenProvider screenProvider)
  12  void setSystemUiDisplay(boolean z, boolean z2)
  4   void startApp(String str, String str2, int i)
  5   void startApp2(String str, String str2, int i, Bundle bundle)
  7   void unregisterCallBack(ScreenCallBack screenCallBack)
  9   void unregisterProvider(ScreenProvider screenProvider)
## com.zhonghong.aidlib.multiscreen.ScreenProvider   (found in: zhset)
  2   int getCurrentScreenPosition(String str)
  3   String getScreenApplication(int i)
  6   boolean isLockScreen()
  5   boolean isViceScreenOn()
  4   void setSystemUiDisplay(boolean z, boolean z2)
  1   void startApp(String str, String str2, int i, Bundle bundle)
## com.zhonghong.aidlib.navi.NaviInterface   (found in: zhset)
  1   void setTurnedTo(int i)
  2   void startNavigation(String str)
## com.zhonghong.aidlib.state.ServiceProvider   (found in: aidlserver)
  1   boolean getBoolean(int i)
  2   int getInt(int i)
  3   String getString(int i)
  4   void setBoolean(int i, boolean z)
  5   void setInt(int i, int i2)
  6   void setString(int i, String str)
## com.zhonghong.aidlib.state.StateCallback   (found in: aidlserver)
  1   void notifyBoolean(int i, boolean z)
  2   void notifyInt(int i, int i2)
  3   void notifyString(int i, String str)
## com.zhonghong.aidlib.state.SystemStateCallBack   (found in: aidlserver)
  4   void notifyACC(boolean z)
  3   void notifyBTPhoneState(int i)
  5   void notifyBTState(boolean z)
  7   void notifyBackState(boolean z)
  1   void notifyHeadlampState(boolean z)
  6   void notifyNetworkState(boolean z)
  8   void notifyPowerState(boolean z)
  2   void notifyVOL(int i)
## com.zhonghong.aidlib.state.SystemStateInterface   (found in: aidlserver)
  5   boolean getBluetoothConnectState()
  6   boolean getBluetoothInCallState()
  4   boolean getNetworkState()
  3   int getTuidNumber()
  1   void registerCallBack(SystemStateCallBack systemStateCallBack)
  2   void unregisterCallBack(SystemStateCallBack systemStateCallBack)
## com.zhonghong.aidlib.state.SystemStateInterface2   (found in: aidlserver)
  8   boolean getBooleanInfo(int i, int i2)
  9   int getIntInfo(int i, int i2)
  11  ServiceProvider getServiceProvider(int i)
  10  String getStringInfo(int i, int i2)
  5   void notifyBooleanInfo(int i, int i2, boolean z)
  6   void notifyIntInfo(int i, int i2, int i3)
  7   void notifyStringInfo(int i, int i2, String str)
  1   void registerCallback(int i, StateCallback stateCallback)
  3   void registerServiceProvider(int i, ServiceProvider serviceProvider)
  12  void setBooleanInfo(int i, int i2, boolean z)
  13  void setIntInfo(int i, int i2, int i3)
  14  void setStringInfo(int i, int i2, String str)
  2   void unregisterCallback(int i, StateCallback stateCallback)
  4   void unregisterServiceProvider(int i, ServiceProvider serviceProvider)
## com.zhonghong.bluetooth.aidl.IBtCtrlInterface   (found in: bt)
  2   String getValue(String str, String str2)
  3   void registerCallback(int i, IBtDataCallback iBtDataCallback)
  1   void setCmd(int i, String str, String str2)
  4   void unregisterCallback(int i, IBtDataCallback iBtDataCallback)
## com.zhonghong.bluetooth.aidl.IBtDataCallback   (found in: bt)
  1   void onDataChanged(int i, String str)
## com.zhonghong.cancommon.ICanDataCallback   (found in: zhset)
  1   void onCanDataNotify(int i)
## com.zhonghong.cancommon.ICanInterface   (found in: zhset)
  3   void execCmd(int i, String str)
  4   void execExtraCmd(int i, String str, String str2)
  5   ElementData getElementDataInfo(int i)
  1   void registerCallback(ICanDataCallback iCanDataCallback)
  2   void unregisterCallback(ICanDataCallback iCanDataCallback)
## com.zhonghong.mcu.IMcuUpdateService   (found in: mcu)
  1   void showMcuUpdateDialog()
## com.zhonghong.mcuupdate.aidlserver.McuUpdateCallback   (found in: mcu)
  2   void onMcuUpdateProgressChanged(int i)
  1   void onMcuUpdateStateChanged(int i)
## com.zhonghong.mcuupdate.aidlserver.McuUpdateCtrlService   (found in: mcu)
  2   void registerCallBack(McuUpdateCallback mcuUpdateCallback)
  1   void update(String str)
## com.zhonghong.mediaaidl.btmusic.IBtMusicAidlInterface   (found in: music)
  5   String getAlbum()
  6   String getArtist()
  4   String getTitle()
  3   boolean isPlaying()
  8   void next()
  12  void pause()
  11  void play()
  10  void playPause()
  9   void prev()
  1   void registerDataChange(IBtMusicAidlListener iBtMusicAidlListener)
  7   void setBtMusicSource()
  2   void unregisterDataChange(IBtMusicAidlListener iBtMusicAidlListener)
## com.zhonghong.mediaaidl.btmusic.IBtMusicAidlListener   (found in: music)
  1   void onId3(String str, String str2, String str3)
  2   void onPlayState(boolean z)
## com.zhonghong.mediaaidl.radio.IRadioAidlInterface   (found in: music)
  14  void fMAMSwitch()
  5   int getFreq()
  4   boolean isAMBand()
  3   boolean isPlaying()
  12  void nextSearch()
  8   void nextStation()
  10  void nextStep()
  7   void playPause()
  13  void prevSearch()
  9   void prevStation()
  11  void prevStep()
  1   void registerDataChange(IRadioAidlListener iRadioAidlListener)
  6   void setRadioSource()
  2   void unregisterDataChange(IRadioAidlListener iRadioAidlListener)
## com.zhonghong.mediaaidl.radio.IRadioAidlListener   (found in: music)
  1   void onBandState(boolean z)
  3   void onFreq(int i, boolean z)
  2   void onPlayState(boolean z)
## com.zhonghong.mediaaidl.usbmusic.IUsbMusicAidlInterface   (found in: music)
  5   String getAlbum()
  6   String getArtist()
  4   String getTitle()
  3   boolean isPlaying()
  8   void next()
  12  void pause()
  11  void play()
  10  void playPause()
  9   void prev()
  1   void registerDataChange(IUsbMusicAidlListener iUsbMusicAidlListener)
  7   void setMusicSource()
  2   void unregisterDataChange(IUsbMusicAidlListener iUsbMusicAidlListener)
## com.zhonghong.mediaaidl.usbmusic.IUsbMusicAidlListener   (found in: music)
  1   void onId3(String str, String str2, String str3)
  2   void onPlayState(boolean z)
## com.zhonghong.mediaaidl.video.IVideoAidlInterface   (found in: music)
  4   String getTitle()
  3   boolean isPlaying()
  6   void next()
  10  void pause()
  9   void play()
  8   void playPause()
  7   void prev()
  1   void registerDataChange(IVideoAidlListener iVideoAidlListener)
  5   void setVideoSource()
  2   void unregisterDataChange(IVideoAidlListener iVideoAidlListener)
## com.zhonghong.mediaaidl.video.IVideoAidlListener   (found in: music)
  2   void onPlayState(boolean z)
  1   void onTitle(String str)
```
## Appendix E — Manifest components of every factory app (auto-generated)

`exp=0xffffffff` means exported=true; `None` means the attribute is absent (default rules apply).

```
##### android.ext.services 1 uid= None | android.ext.services.apk
  SVC .notification.Ranker exp= 0xffffffff perm= android.permission.BIND_NOTIFICATION_RANKER_SERVICE proc= None actions= ['android.service.notification.NotificationRankerService']
##### com.car.phoneprovider 20180720 uid= None | com.car.phoneprovider.apk
  PRV com.car.phone_data exp= 0xffffffff r= None w= None perm= None
  ACT com.car.phoneprovider.ui.MainActivity exp= None actions= [] cats= []
##### com.polstar.polnav6 @0x7f040002 uid= None | com.polstar.polnav6.apk
  SVC .PolnavELService exp= None perm= None proc= None actions= ['com.polstar.polnav6.IPolnavELService']
  SVC .BGService exp= None perm= None proc= None actions= []
  RCV .ShutdownReceiver exp= None perm= None actions= ['android.intent.action.ACTION_SHUTDOWN', 'android.intent.action.QUICKBOOT_POWEROFF']
  RCV .CoordinateReceiver exp= None perm= None actions= ['com.polstar.navi.COORDINATE']
  ACT .MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zh.auxin 202110211840 uid= android.uid.system | com.zh.auxin.apk
  ACT com.zh.auxin.MainActivity exp= None actions= ['android.intent.action.VIEW'] cats= ['LAUNCHER']
##### com.zh.test 202301041609 uid= android.uid.system | com.zh.test.apk
  SVC com.zh.test.fragment.service.CmdService exp= None perm= None proc= None actions= []
  RCV com.zh.test.fragment.broadcast.BootReceiver exp= None perm= None actions= ['android.intent.action.BOOT_COMPLETED']
  ACT .MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.mediaconfigtool 202009281030 uid= android.uid.system | com.zhonghong.mediaconfigtool.apk
  RCV com.zhonghong.mediaconfigtool.receiver.USBReceiver exp= 0x0 perm= None actions= ['android.intent.action.MEDIA_MOUNTED', 'android.intent.action.MEDIA_UNMOUNTED', 'android.intent.action.MEDIA_EJECT']
  ACT com.zhonghong.mediaconfigtool.MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.screensaver 202209191426 uid= android.uid.system | com.zhonghong.screensaver.apk
  SVC com.zhonghong.screensaver.service.ScreenService exp= 0xffffffff perm= None proc= None actions= ['com.zhonghong.screensaver.ScreenService']
  RCV com.zhonghong.screensaver.receiver.MountBroadcastReceiver exp= 0x0 perm= None actions= ['android.intent.action.MEDIA_MOUNTED', 'android.intent.action.MEDIA_EJECT', 'android.intent.action.MEDIA_UNMOUNTED', 'android.intent.action.BOOT_COMPLETED']
##### com.zhonghong.update 202302071016 uid= android.uid.system | com.zhonghong.update.apk
  SVC com.zhonghong.update.service.UpdateService exp= 0xffffffff perm= None proc= None actions= ['com.zhonghong.ctrl_update']
  ACT .MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.uuidtool 202011241500 uid= android.uid.system | com.zhonghong.uuidtool.apk
  RCV com.zhonghong.receiver.USBBroadcastReceiver exp= 0x0 perm= None actions= ['android.intent.action.MEDIA_MOUNTED', 'android.intent.action.MEDIA_EJECT']
  ACT com.zhonghong.uuidtool.MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.yzpicture 202206021631 uid= android.uid.system | com.zhonghong.yzpicture.apk
  ACT com.zhonghong.yzpicture.view.MainActivity exp= 0xffffffff actions= ['android.intent.action.VIEW', 'com.zhonghong.usb_music_activity'] cats= ['LAUNCHER', 'DEFAULT']
##### com.zhonghong.yzvideo 20240105.01 uid= android.uid.system | com.zhonghong.yzvideo.apk
  SVC com.zhonghong.yzvideo.service.VideoService exp= 0xffffffff perm= None proc= None actions= ['com.zhonghong.usb_video_action']
  ACT com.zhonghong.yzvideo.view.MainActivity exp= None actions= ['android.intent.action.VIEW', 'com.zhonghong.video_activity'] cats= ['LAUNCHER']
##### com.zh.settings 202401051446 uid= android.uid.system | com_zh_settings.apk
  SVC com.zh.settings.service.SetService exp= None perm= None proc= None actions= ['com.zhonghong.action.set']
  ACT com.zh.settings.MainActivity exp= None actions= ['com.zhonghong.action.set'] cats= ['LAUNCHER', 'DEFAULT']
  ACT com.zh.settings.SoundActivity exp= None actions= [] cats= []
  ACT com.zh.settings.SystemActivity exp= None actions= [] cats= []
  ACT com.zh.settings.WifiActivity exp= None actions= [] cats= []
  ACT com.zh.settings.DisplayActivity exp= None actions= [] cats= []
  ACT com.zh.settings.FieldActivity exp= None actions= [] cats= []
  ACT com.zh.settings.EqActivity exp= None actions= ['com.zhonghong.action.eq'] cats= ['DEFAULT']
  ACT com.zh.settings.ZXingActivity exp= None actions= [] cats= []
##### com.zh.yzradio 202211090303 uid= android.uid.system | com_zh_yzradio.apk
  ACT com.zh.yzradio.MainActivity exp= None actions= ['android.intent.action.VIEW'] cats= ['LAUNCHER']
  ACT com.zh.yzradio.rds.RdsViewActivity exp= None actions= [] cats= []
##### com.zhonghong.aidlserver 1.0 uid= None | com_zhonghong_aidlserver.apk
  SVC com.zhonghong.aidlserver.service.SystemStateService exp= 0xffffffff perm= None proc= :systemstate actions= ['com.zhonghong.aidlserver.service.SystemStateService']
  SVC com.zhonghong.aidlserver.service.SystemStateService2 exp= 0xffffffff perm= None proc= :systemstate2 actions= ['com.zhonghong.aidlserver.service.SystemStateService2']
  ACT com.zhonghong.aidlserver.MainActivity exp= None actions= [] cats= []
##### com.zhonghong.bluetooth 202401051453 uid= android.uid.system | com_zhonghong_bluetooth.apk
  SVC com.zhonghong.bluetooth.service.BtCtrlManagerService exp= None perm= None proc= None actions= ['com.zhonghong.bluetooth.START_BtCtrlService']
  RCV com.zhonghong.bluetooth.receiver.BootReceiver exp= 0x0 perm= None actions= ['com.zhonghong.zuiserver.BROADCAST']
  RCV com.zhonghong.bluetooth.receiver.UpdateSystemReceiver exp= 0xffffffff perm= None actions= ['zhonghong.intent.action.START_UPDATE', 'zhonghong.intent.action.END_UPDATE', 'com.zhonghong.bluetooth.call_last_number']
  RCV com.zhonghong.bluetooth.receiver.EasyConnectedReceiver exp= 0xffffffff perm= None actions= ['net.easyconn.bt.checkstatus', 'net.easyconn.bt.connected', 'net.easyconn.app.quit', 'net.easyconn.a2dp.release', 'net.easyconn.a2dp.acquire', 'net.easyconn.bt.connect', 'net.easyconn.connection.break']
  PRV com.zhonghong.bluetooth.provider.btinfo exp= 0xffffffff r= None w= None perm= None
  ACT com.zhonghong.bluetooth.MainActivity exp= None actions= ['android.bluetooth_btsettings', 'android.bluetooth_btdial'] cats= ['LAUNCHER', 'DEFAULT']
  ACT com.zhonghong.bluetooth.MusicActivity exp= None actions= ['android.bluetooth_btmusic'] cats= ['DEFAULT']
  ACT com.zhonghong.bluetooth.ContactActivity exp= None actions= [] cats= []
  ACT com.zhonghong.bluetooth.SetActivity exp= None actions= [] cats= []
##### com.zhonghong.can 2024.01.05 uid= android.uid.system | com_zhonghong_can.apk
  SVC com.zhonghong.can.CanService exp= 0xffffffff perm= None proc= None actions= ['com.zhonghong.intent.action.can']
##### com.zhonghong.carsetting 202401171303 uid= android.uid.system | com_zhonghong_carsetting.apk
  ACT com.zhonghong.carsetting.MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.launcher 202401051430 uid= android.uid.system | com_zhonghong_launcher.apk
  RCV com.zhonghong.launcher.receiver.BootReceiver exp= 0x0 perm= None actions= ['com.zhonghong.zuiserver.BROADCAST']
  ACT com.zhonghong.launcher.activity.LauncherActivity exp= None actions= [] cats= ['HOME', 'LAUNCHER', 'DEFAULT']
  ACT com.zhonghong.launcher.activity.MediaActivity exp= None actions= ['com.zhonghong.launcher.MEDIA_ACTION'] cats= ['DEFAULT']
  ACT com.zhonghong.launcher.activity.NaviActivity exp= None actions= ['com.zhonghong.launcher.NAVI_ACTION'] cats= ['DEFAULT']
##### com.zhonghong.logic 202401051107 uid= android.uid.system | com_zhonghong_logic.apk
  SVC com.zhonghong.logic.service.LogicService exp= None perm= None proc= None actions= ['com.zhonghong.logic.service.LogicService']
  SVC com.zhonghong.logic.multouth.MultiTouchService exp= None perm= None proc= None actions= []
  RCV com.zhonghong.logic.receiver.BootReceiver exp= None perm= None actions= ['android.intent.action.BOOT_COMPLETED']
  ACT com.zhonghong.logic.MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.mcu 20170117 uid= android.uid.system | com_zhonghong_mcu.apk
  SVC com.zhonghong.mcu.McuUpdateService exp= None perm= None proc= None actions= ['com.zhonghong.mcu.START_MCUUPDATE']
  SVC com.zhonghong.mcuupdate.aidlserver.McuCtrlServer exp= None perm= None proc= None actions= ['com.zhonghong.action.mcuupdate']
##### com.zhonghong.mediascan2 20230321.1.0 uid= None | com_zhonghong_mediascan2.apk
  PRV com.zhonghong.media exp= 0xffffffff r= None w= None perm= None
  ACT com.zhonghong.mediascan.MainActivity exp= None actions= [] cats= ['LAUNCHER']
##### com.zhonghong.yzmusic 202303211539 uid= android.uid.system | com_zhonghong_yzmusic.apk
  SVC com.zhonghong.music.model.service.UsbMusicService exp= 0xffffffff perm= None proc= None actions= ['com.zhonghong.usb_music_action']
  ACT com.zhonghong.yzmusic.view.MainActivity exp= 0xffffffff actions= ['android.intent.action.VIEW', 'com.zhonghong.usb_music_activity', 'com.zhonghong.usb_music_list', 'com.zhonghong.intent.action.music'] cats= ['LAUNCHER', 'DEFAULT', 'DEFAULT']

```
## Appendix F — Essentials from the on-device probe reports (identifiers removed)

Two probe runs were taken with an ordinary app (`uid u0_aXX`). Serial numbers, host/network identifiers and GPS coordinates were removed.

### F.1 Registered system services (`service list`, full)
```
0	zhupdate_service: [com.zhonghong.update.aidl.IUpdateInterface]
1	_logic_service: [com.logic.common.aidl.ILogicInterface]
2	drm.drmManager: [drm.IDrmManagerService]
3	media.camera: [android.hardware.ICameraService]
4	android.service.gatekeeper.IGateKeeperService: [android.service.gatekeeper.IGateKeeperService]
5	contexthub_service: [android.hardware.location.IContextHubService]
6	launcherapps: [android.content.pm.ILauncherApps]
7	shortcut: [android.content.pm.IShortcutService]
8	media_router: [android.media.IMediaRouterService]
9	media_session: [android.media.session.ISessionManager]
10	graphicsstats: [android.view.IGraphicsStats]
11	dreams: [android.service.dreams.IDreamManager]
12	network_time_update_service: []
13	appwidget: [com.android.internal.appwidget.IAppWidgetService]
14	soundtrigger: [com.android.internal.app.ISoundTriggerService]
15	jobscheduler: [android.app.job.IJobScheduler]
16	hardware_properties: [android.os.IHardwarePropertiesManager]
17	usb: [android.hardware.usb.IUsbManager]
18	DockObserver: []
19	audio: [android.media.IAudioService]
20	wallpaper: [android.app.IWallpaperManager]
21	country_detector: [android.location.ICountryDetector]
22	location: [android.location.ILocationManager]
23	devicestoragemonitor: []
24	notification: [android.app.INotificationManager]
25	recovery: [android.os.IRecoverySystem]
26	updatelock: [android.os.IUpdateLock]
27	connectivity: [android.net.IConnectivityManager]
28	ethernet: [android.net.IEthernetManager]
29	wifiscanner: [android.net.wifi.IWifiScanner]
30	wifi: [android.net.wifi.IWifiManager]
31	wifip2p: [android.net.wifi.p2p.IWifiP2pManager]
32	netpolicy: [android.net.INetworkPolicyManager]
33	netstats: [android.net.INetworkStatsService]
34	network_score: [android.net.INetworkScoreService]
35	textservices: [com.android.internal.textservice.ITextServicesManager]
36	network_management: [android.os.INetworkManagementService]
37	clipboard: [android.content.IClipboard]
38	statusbar: [com.android.internal.statusbar.IStatusBarService]
39	device_policy: [android.app.admin.IDevicePolicyManager]
40	deviceidle: [android.os.IDeviceIdleController]
41	lock_settings: [com.android.internal.widget.ILockSettings]
42	uimode: [android.app.IUiModeManager]
43	accessibility: [android.view.accessibility.IAccessibilityManager]
44	input_method: [com.android.internal.view.IInputMethodManager]
45	pinner: []
46	input: [android.hardware.input.IInputManager]
47	window: [android.view.IWindowManager]
48	alarm: [android.app.IAlarmManager]
49	content: [android.content.IContentService]
50	account: [android.accounts.IAccountManager]
51	telephony.registry: [com.android.internal.telephony.ITelephonyRegistry]
52	mount: [IMountService]
53	scheduling_policy: [android.os.ISchedulingPolicyService]
54	webviewupdate: [android.webkit.IWebViewUpdateService]
55	usagestats: [android.app.usage.IUsageStatsManager]
56	battery: []
57	sensorservice: [android.gui.SensorServer]
58	processinfo: [android.os.IProcessInfoService]
59	permission: [android.os.IPermissionController]
60	dbinfo: []
61	gfxinfo: []
62	meminfo: []
63	procstats: [com.android.internal.app.procstats.IProcessStats]
64	activity: [android.app.IActivityManager]
65	user: [android.os.IUserManager]
66	package: [android.content.pm.IPackageManager]
67	display: [android.hardware.display.IDisplayManager]
68	power: [android.os.IPowerManager]
69	appops: [com.android.internal.app.IAppOpsService]
70	batterystats: [com.android.internal.app.IBatteryStats]
71	netd: [android.net.INetd]
72	media.resource_manager: [android.media.IResourceManagerService]
73	media.player: [android.media.IMediaPlayerService]
74	media.drm: [android.media.IMediaDrmService]
75	media.extractor: [android.media.IMediaExtractorService]
76	media.sound_trigger_hw: [android.hardware.ISoundTriggerHwService]
77	media.radio: [android.hardware.IRadioService]
78	media.audio_policy: [android.media.IAudioPolicyService]
79	media.audio_flinger: [android.media.IAudioFlinger]
80	media.codec: [android.media.IMediaCodecService]
81	android.security.keystore: [android.security.IKeystoreService]
82	zhonghong.mcu: [android.zhonghong.IMcuService]
83	batteryproperties: [android.os.IBatteryPropertiesRegistrar]
84	gpu: [android.ui.IGpuService]
85	SurfaceFlinger: [android.ui.ISurfaceComposer]
```

### F.2 Selected system properties
```
[CarSettingKey]: [0]
[dev.bootcomplete]: [1]
[persist.phone.easyconnect]: [true]
[persist.sys.country]: []
[persist.sys.dalvik.vm.lib.2]: [libart.so]
[persist.sys.default.launcher]: [com.zhonghong.launcher/com.zhonghong.launcher.activity.LauncherActivity]
[persist.sys.default_navi]: [18]
[persist.sys.default_voice_call]: [20]
[persist.sys.language]: [fa]
[persist.sys.locale]: [fa-IR]
[persist.sys.localevar]: []
[persist.sys.logger.config]: [kmsg,logcat]
[persist.sys.max_alarm]: [30]
[persist.sys.max_bluetooth_sco]: [30]
[persist.sys.max_dtmf]: [30]
[persist.sys.max_enforced]: [30]
[persist.sys.max_music]: [30]
[persist.sys.max_navi]: [30]
[persist.sys.max_notification]: [30]
[persist.sys.max_ring]: [30]
[persist.sys.max_system]: [30]
[persist.sys.max_tts]: [30]
[persist.sys.max_voice_call]: [30]
[persist.sys.start.logic.first]: [1]
[persist.sys.timezone]: [Asia/Tehran]
[persist.sys.usb.config]: [adb]
[persist.sys.webview.vmsize]: [104857600]
[persist.zh.force_no_anim]: [true]
[persist.zh.forced_no_orient]: [true]
[persist.zh.google.py]: [1]
[persist.zh.login]: [true]
[ro.board.platform]: [s5p4418]
[ro.boot.hardware]: [zh_dragon]
[ro.boot.nxp4330.ext_audio]: [,ape,wma,ac3]
[ro.boot.nxp4330.ext_media]: [true]
[ro.boot.nxp4330.ext_video]: [,avi,flv,ts,mkv,rm,rmvb,asf,wmv]
[ro.boot.selinux]: [permissive]
[ro.build.fingerprint]: [Android/aosp_zh_dragon/zh_dragon:7.1.2/N2G48H/builde02141541:userdebug/release-keys]
[ro.build.tags]: [release-keys]
[ro.build.type]: [userdebug]
[ro.build.version.release]: [7.1.2]
[ro.hardware]: [zh_dragon]
[ro.kernel.android.bt]: [goc_serial]
[ro.kernel.android.gps]: [ttyAMA0]
[ro.kernel.android.mcu]: [ttyAMA2]
[ro.mcu.baudRate]: [2]
[ro.product.board]: [zh_dragon]
[ro.product.brand]: [Android]
[ro.product.cpu.abi2]: [armeabi]
[ro.product.cpu.abi]: [armeabi-v7a]
[ro.product.cpu.abilist32]: [armeabi-v7a,armeabi]
[ro.product.cpu.abilist64]: []
[ro.product.cpu.abilist]: [armeabi-v7a,armeabi]
[ro.product.device]: [zh_dragon]
[ro.product.first_api_level]: [25]
[ro.product.locale]: [en-US]
[ro.product.manufacturer]: [NEXELL]
[ro.product.model]: [AOSP on zh_dragon]
[ro.product.name]: [aosp_zh_dragon]
[ro.zh.inputmethodbottom]: [30]
[ro.zh.multi.audio_channel]: [true]
[ro.zh.onlysetscan]: [true]
[ro.zh.regudevice]: [2]
[sys.boot_completed]: [1]
[sys.carlifevr]: [false]
[sys.stream.duckvol]: [33]
[sys.usb.config]: [adb]
[sys.usb.configfs]: [1]
[sys.usb.controller]: [c0040000.dwc2otg]
[sys.usb.ffs.ready]: [1]
[sys.usb.state]: [adb]
[sys.zh.recoveryrun]: [true]
[sys.zh.regularvol]: [-10]
[zh.navi.pidlist]: [7767,1673]
```

### F.3 TTY drivers and device nodes
```
/dev/tty             /dev/tty        5       0 system:/dev/tty
/dev/console         /dev/console    5       1 system:console
/dev/ptmx            /dev/ptmx       5       2 system
g_serial             /dev/ttyGS    250 0-3 serial
pty_slave            /dev/pts      136 0-1048575 pty:slave
pty_master           /dev/ptm      128 0-1048575 pty:master
ttyAMA               /dev/ttyAMA   204 64-77 serial

crw------- 1 root   root          89,  10 1970-01-01 03:30 i2c-10
crw------- 1 root   root          89,   2 1970-01-01 03:30 i2c-2
crw------- 1 root   root          89,   3 1970-01-01 03:30 i2c-3
crw------- 1 root   root          89,   4 1970-01-01 03:30 i2c-4
crw-rw-rw- 1 root   root           5,   0 1970-01-01 03:30 tty
crw-rw-rw- 1 system system       204,  64 1970-01-01 03:30 ttyAMA0
crw-rw-rw- 1 system system       204,  65 2026-10-26 13:38 ttyAMA1
crw-rw-rw- 1 system system       204,  66 2026-10-26 13:38 ttyAMA2
crw-rw-rw- 1 system system       204,  67 2009-09-01 04:30 ttyAMA3
crw-rw-rw- 1 system system       204,  68 1970-01-01 03:30 ttyAMA4
crw-rw-rw- 1 system system        10,  56 1970-01-01 03:30 usb_i2c
```

### F.4 Serial port samples (hex, first bytes read in 1.5 s)
```
ttyAMA0  GPS: NMEA text sentences ($GNGGA ...)  [content not reproduced: it contains the car position]
ttyAMA1  run 1: C0 CB C4 00 70 0E 0A 02 09 10 00 B8 67 AF 77 1F 26 46 FC C0
ttyAMA1  run 2: C0 D3 74 00 B8 13 05 01 02 00 01 00 FB 69 C0
ttyAMA2  run 1 (MCU link per ro.kernel.android.mcu): FF AA 50 00 00 A0 15 02 D2 09 FD 00 0D 00 F0 00 00 00 00 DC 0A
ttyAMA2  run 2: (no data)
ttyAMA3 / ttyAMA4: (no data)
```

### F.5 Storage layout
```
/mnt:
appfuse
asec
expand
media_rw
obb
runtime
sdcard
secure
user

/sdcard:
Android
AudioRecorder
CarProbe
Download
backup
backups
easyconn
qsdlogs.qsd
qsdvideo
show_test.png
xbrowser
zhmedia

/storage:
emulated
self
udisk1
udisk2

adb /dev/usb-ffs/adb functionfs rw,relatime 0 0
/dev/block/vold/public:8_1 /mnt/media_rw/udisk2 vfat rw,dirsync,nosuid,nodev,noexec,relatime,uid=1023,gid=1023,fmask=0007,dmask=0007,allow_utime=0020,codepage=437,iocharset=iso8859-1,shortname=mixed,utf8,errors=remount-ro 0 0
/dev/block/mmcblk0p2 /system ext4 ro,seclabel,relatime,data=ordered 0 0
/dev/block/mmcblk0p9 /data ext4 rw,seclabel,nosuid,nodev,noatime,data=ordered 0 0
tmpfs /storage tmpfs rw,seclabel,relatime,mode=755,gid=1000 0 0
/dev/fuse /storage/emulated fuse rw,nosuid,nodev,noexec,noatime,user_id=1023,group_id=1023,default_permissions,allow_other 0 0
/dev/fuse /storage/udisk2 fuse rw,nosuid,nodev,noexec,noatime,user_id=1023,group_id=1023,default_permissions,allow_other 0 0
tmpfs /storage/self tmpfs rw,seclabel,relatime,mode=755,gid=1000 0 0
```

## Appendix G — Detailed device-probe verification (27 October 2026)

### G.1 Executive result

The probe conclusively demonstrates that an ordinary app can bind to the CAN service, read the decoded signal collection, register a CAN callback, and observe changes while physical controls are operated. It also demonstrates working read access to `_logic_service` and the MCU service, successful registration of the Logic callback and the System State Broker callback, and functioning USB-music/content-library access.

This resolves the original handoff's broad concern that these services might be unreachable from a third-party launcher. It does **not** prove every command is usable: MCU writes were not tested; direct Bluetooth control remains unavailable in this run; the Video service did not connect; and some vehicle signal meanings still require a targeted test.

### G.2 CAN service and tested signal meanings

#### G.2.1 Service access — CONFIRMED

- `bindService` to `com.zhonghong.can/.CanService` returned `true`.
- The remote descriptor was `com.zhonghong.cancommon.ICanInterface`.
- `registerCallback` succeeded.
- `getElementDataInfo` returned data for modules 1–4; the initial decoded snapshot contained 115 signals.
- The log contains live `CAN-CHG` changes after physical actions, demonstrating both readable data and working notifications from this normal app.
- `zh_can_service` remained unregistered, but this is not a blocker: binding directly to `com.zhonghong.can/.CanService` worked.

#### G.2.2 Signals now confirmed on this car

| Signal / area | Observed evidence | Usable interpretation |
|---|---|---|
| Four side doors, frame 564 | For each of driver, front passenger, rear left and rear right, the field changed `1 -> 0` when opened and `0 -> 1` when closed. | `0 = open`, `1 = closed` for these door fields. Trunk and bonnet were not separately exercised in this run. |
| Position lamps, frame 564/963 | Position switch changed `0 -> 1` when turned on and back to `0` when lights were turned off; output status also changed. | `1` means the switch/input is active; use frame 963 lamp output for actual output state. |
| Low beam, frame 564/963 | Low-beam switch `0 -> 1` and output `0 -> 1`; both returned to `0` when switching to high beam / lights off. | `1 = active/on` for the switch bit; output status is the stronger indication of which lamp is currently on. |
| High beam, frame 564/963 | High-beam switch `0 -> 1`, output `0 -> 1`; both returned to `0` at lights-off. | `1 = active/on`. |
| Front and rear fog lamps, frame 564/963 | The corresponding output changed `0 -> 1`; the corresponding switch bit briefly changed `0 -> 1 -> 0`. | Output status confirms the lamp state. Switch bit behaves like a momentary/pulsed input in this test; do not assume it stays high while the lamp is on. |
| Turn indicators, frame 564/963 | Left/right input signals and lamp outputs toggled during the explicitly marked left/right indicator actions; `Indicator_Lamps_Command` showed `2` for left and `1` for right. | Indicator input/output is dynamic and flashes; don't interpret one instantaneous `0` as proof that the indicator was not selected. |
| Central lock/unlock | The marked lock/unlock actions produced short-lived changes in `Door_Lock_Output__Status`, `Master_Lock_Switch_Status` and the driver-door actuator signal; values returned to baseline quickly. | **Action response confirmed, persistent lock state not confirmed.** Treat these as pulses until a reliable stable locked/unlocked signal is isolated. |
| Hazard lights, frame 564/963 | Both left/right lamp outputs flashed together; `Hazard_LED_Output_Status` changed with the hazard action. `Hazard_Switch_Status` pulsed `0 -> 1 -> 0`. | Confirmed functional detection. Treat the switch as a pulse, not a durable on/off state. |
| Brake pedal, frames 564/577/963 | Pressing changed the pedal signals `0 -> 1` and brake-lamp output `0 -> 1`; releasing changed them back. | `1 = pressed`; `0 = released`. |
| Handbrake, frame 564 | During the marked handbrake-off action, `Hand_Brake_Switch_Status` changed `1 -> 0`; on action changed `0 -> 1`. | `1 = engaged`; `0 = released`, independently confirmed by the test. |
| Driver seat belt, frames 564/722 | At `DRIVER_BELT_FASTENED`, direct switch and frame-722 lock-recognition changed `1 -> 0`; at `DRIVER_BELT_UNFASTENED`, both changed `0 -> 1`. | For the tested driver-belt signals: `0 = fastened/locked`, `1 = unfastened/unlocked`. |
| Gear selector, frame 1218 | Stable values seen at the marked Neutral and Drive positions were `11` and `12`. During transitions the sequence was `9 -> 10 -> 11` (P toward N) and `12 -> 11 -> 10 -> 9` (D back toward P). Reverse lamp and `onBackStateChanged=true` occurred around value `10`. | Strongly supported mapping for this unit: `9 = P`, `10 = R`, `11 = N`, `12 = D`. Value `10` is supported by reverse lamp/back-state correlation, though there was no standalone `MARK GEAR_REVERSE` checkpoint. |
| Reverse state | Moving through reverse caused Logic `onBackStateChanged=true/false`; System State Broker emitted `back = 1/0`; the sample's `back_state` followed the reverse interval. | Reverse observation from Logic/System State is confirmed. The factory system owns the camera switching and overlay; the launcher must not cover the camera view. |
| Exterior lights / Logic event | `onIllStateChanged=true` appeared as the position/low-beam lamps came on; `false` appeared at lights-off. | Confirms a usable illumination callback for day/night UI logic. |
| Volume notifications | System State Broker emitted volume values `10, 11, 12, 13, 14`; subsequent MCU domain-2 read-all reported `SYS_VOL=14`. | Physical volume changes are observable via the broker; MCU current volume can be read. This does not yet prove that a third-party app can write volume through MCU. |
| Ambient temperature | CAN frame 695 alternated between raw `110` and `112`. Using the documented factory formula, these correspond to `15°C` and `16°C`. | Data is accessible. The one-degree alternation in this recording should be treated as observed behaviour, not assumed sensor accuracy. |

#### G.2.3 Still unresolved or only partly tested

| Signal / area | What the log actually proves | Updated status / next evidence needed |
|---|---|---|
| Passenger seat belt, frames 564/722 | Initial direct switch value was `1` and passenger frame-722 recognition was `3`, but neither changed during the marked passenger-belt actions. | **Still unconfirmed.** Repeat with the passenger seat occupied as required by the car's sensor logic; log fasten/unfasten and occupancy changes. Do not infer polarity from the driver's signal. |
| Vehicle speed, frame 1218 byte 5 | Every sample logged `speed@1218=0`, including while the selector was briefly in D; the vehicle did not register movement in this test. | **Read path works, units unconfirmed.** Collect a log while the car is actually moving, with a passenger operating the probe, and compare with the instrument cluster. Do not test or operate the probe while driving. |
| Odometers, frames 800 and 1219 | CarCheck’s short field output printed `53342` for both because it reported only the low 16 bits. The raw frame bytes contain the full four-byte value `5E D0 15 02` in both frames. | **Corrected in Appendix I:** factory `CarSetInfo` combines the lower and upper 16-bit halves into `0x0215D05E` (34,984,030 raw units); the factory maintenance screen divides by 1000 and floors, yielding 34,984 km. This follows the factory parser and both matching frames; still compare with the instrument cluster as a physical sanity check. |
| `KeyPositionStatus` (frame 577) | It toggled between `0` and `1` during several door operations, sometimes around a close/open event, but the test did not isolate a key-position action. | **Meaning still unconfirmed.** Do not use it as ignition/key-present state in the launcher until a dedicated test isolates each button/ignition state. Use Logic/MCU ACC and power fields instead. |
| Passenger belt / airbag frame 722 | The driver-belt recognition bits were correlated with belt action. Crash-intensity, ECU-error and passenger-airbag-cutoff fields did not undergo a controlled state change; the live checksum/counter changed normally. | **Airbag-related semantics remain unverified.** Do not display crash, airbag-health or passenger-airbag warnings based only on these raw values. The changing checksum and alive counter are not themselves faults. |
| Wipers | The `WIPER_ON` / `WIPER_OFF` markers produced no observable change in the logged wiper switch/output fields; frame snapshots differed only in the airbag checksum/counter. | **Not confirmed by this run.** Repeat with a clearly visible/physical wiper action and check `Wiper_*_Switch_Status`, `Front_Wiper_Output_Status`, `Rear_Wiper_Output_Status` and `Wash_Output_Status`. |
| Driver window | `WINDOW_DRIVER_DOWN` / `WINDOW_DRIVER_UP` markers did not yield a change in the logged window fields or frame-564 snapshot; the owner reports window sensor data is not useful on this vehicle. | **Not usable for the agreed launcher.** Omit window widgets; no further test is planned unless a distinct physical sensor/output is identified.
| Trunk, bonnet, rear wiper, rear windows and other auxiliary signals | Not isolated by a clear action in this log. | **Untested** beyond availability of the initial decoded signal collection. |

**Odometer correction:** the short CarCheck summary displayed only the low 16-bit part (`53342`), not the full odometer. Raw frames and the factory parser show a 32-bit value; see Appendix I. The earlier 53 km interpretation is wrong and must not be used.

### G.3 System services and callbacks

| Interface | Test result | Updated status |
|---|---|---|
| `_logic_service` / `ILogicInterface` | Descriptor matched; getter calls for ACC, power, reverse, illumination, running, AVM, AUX and source/focus returned values; callback registration succeeded; callbacks produced illumination and reverse events. | **Third-party read calls and callbacks confirmed.** `setCmd` writes/source changes were not tested. |
| `zhonghong.mcu` / `IMcuService` | `getTest()=100`; read-all for MCU domains 1 (radio), 2 (settings) and 3 (system) returned `ok=1`; keyed reads for `SYS_VOL`, `SYS_MUTE`, `ACC_STATE`, `BACK_SIGNAL`, `ILL_STATE`, and `MCU_SOURCE` succeeded. | **Third-party reads confirmed.** No MCU setting writes were performed. |
| MCU keyed reads that failed | Keyed domain-2 reads of `brightness` and `EQ1k` returned `ok=0`; keyed domain-1 `getfreq` returned `ok=0` while radio read-all said status `off`. Domain-1 read without the input parcel also failed. | Avoid relying on these keyed reads without further diagnosis. Prefer the successful domain read-all where appropriate; test one known-safe write/read-back at a time before implementing settings controls. |
| `SystemStateService` | Bind and callback registration succeeded; Bluetooth connection/in-call state getters returned values; callbacks reported volume changes, reverse-state changes and Bluetooth phone-state changes. | **Primary useful state/callback API confirmed.** |
| `SystemStateService2` | Binding succeeded and descriptor was read. The generic provider/store methods were not exercised. | **Binding only confirmed; provider calls remain untested.** |
| `zh_can_service` | Lookup returned `null`. | Not a blocker: bind to `com.zhonghong.can/.CanService`, which worked. |
| `zhbt_service` | Lookup returned `null` initially and again after the observed call while `Settings.System bt_connected=true`; the log did not query the service while the call was explicitly active. | **Direct BT control service still unavailable in this run**, even with the phone connected and call-state activity. |
| `sys.btcall` | Initially unset; it became `true` during a call and `false` after the call ended. | **Useful call-in-progress fallback confirmed** on this unit. |
| `Settings.System` Bluetooth keys | `bt_connected=true`, `key_bluetooth_state=2`; signal and battery keys returned values. | Readable and useful for connection/status UI, though exact enumerations should be documented before display. |

### G.4 Bluetooth, media, providers and factory activities

- **Bluetooth provider:** `btbase` and `btmusic` each returned one row, but most state/device/call/music metadata fields were `null`; `btcontacts` returned zero rows. The phone provider's `/contacts` and `/call_log` queries returned null cursors; `/contacts_favorite` returned zero rows. These results do not currently support a reliable custom contacts/call-log UI.
- **Bluetooth state/call monitoring:** although direct `zhbt_service` control was unavailable, the System State Broker emitted `btPhoneState` events; `sys.btcall` was `true` during the observed call and `false` after it ended. Use these only for state detection, not call-control commands.
- **USB music service:** binding, interface descriptor, `isPlaying`, and listener registration succeeded. `getTitle` and `getArtist` returned strings with lengths 62 and 15, but the probe logs only the lengths, not the actual text. Music control methods (`playPause`, `next`, `prev`, etc.) were not invoked, so control behaviour is not yet tested.
- **Media library provider:** `content://com.zhonghong.media/audio` and `/id3` each returned 10 rows. `/video`, `/image` and `/txt` returned zero rows in this scan; `/scan_state` returned one row with `state=2` (meaning not decoded in this test). The read path is confirmed; contents depend on current mounted/scanned media.
- **USB video service:** the bind request returned `true`, but `onServiceConnected` did not arrive within 5 seconds; source analysis shows the currently declared service `onBind()` returns `null`. Do not regard the AIDL control binder as usable. Factory video activity action resolution itself succeeded; this does not indicate video playback is broken.
- **MCU radio read:** domain-1 read-all succeeded but returned radio `status=off`, `HAVESIG=false`, and `RADIO_AREA=0`. `getfreq` keyed read failed. This proves only that the service can return its radio domain, not that tuning/control works from this app.
- **Factory screen intents:** the probe resolved the radio, car-settings, AUX, Android Settings, ES File Explorer, factory music/video, BT dial/settings/music, EQ, system settings, and factory launcher media/navigation actions. Resolution proves an activity can be found, not that every screen was actually launched during this test.
- **Overlay:** adding an Android `TYPE_PHONE` overlay succeeded on the test unit. The factory call/reverse UI can still conflict with custom overlays; avoid duplicating its call UI and never obscure the reverse-camera view.
- **Beep broadcast:** the probe marked that it sent `com.zhonghong.android.key` with `data=4608`, but the log does not record whether a beep was actually heard. Effect remains unconfirmed.

### G.5 Status-label changes applied in this verified copy

1. **Section 3.0, row 1:** CAN binding, reading and callback registration are now marked `[DEVICE]` confirmed from a normal third-party app; per-signal semantics remain classified separately in Section 3.1 and Appendix G.2.
2. **Section 3.0, rows 2–3:** `_logic_service` getters/callbacks and MCU domain read calls are marked confirmed; all MCU and Logic writes remain untested.
3. **Section 3.0, row 4 / Section 3.4:** the Bluetooth-control service is documented as returning `null` in the initial and after-call lookups. The probe did not query it while the call was explicitly active, and direct control remains unproven.
4. **Section 3.0, row 5 / Section 3.4:** provider schema queries succeeded, but useful Bluetooth metadata fields were null and `btcontacts` was empty in this run.
5. **Section 3.1 / Section 5:** lamp polarity for tested lamps, four side doors, driver-belt signals, brake pedal, handbrake and the gear map are classified as device-observed; unresolved meanings are listed in Appendix G.2.3. The launcher UI still explicitly omits every seat-belt widget per the owner’s product decision.
6. **Section 3.5:** the Video service binding attempt is recorded as unsuccessful at the connection stage: `bindService` returned `true`, but no `onServiceConnected` arrived within 5 seconds.
7. **Section 5:** serial-port purposes remain untested. Do not use those ports in the launcher; the verified Binder interfaces make direct UART access unnecessary.
8. **Section 3.6:** `SystemStateService` getter/callback usage is confirmed; `SystemStateService2` was only bound, not functionally tested.

### G.6 Launcher implementation guidance based on this run

- Build the vehicle-state layer around `CanService` callbacks and refresh reads; do not poll or read `/dev/ttyAMA*` ports.
- Use `_logic_service` / System State Broker events for ACC, power, reverse, illumination, volume and Bluetooth call/connection state. Keep `Settings.System back_state` as a reverse-state fallback if useful.
- Use the current tested gear values only with the above mapping: `9=P`, `10=R`, `11=N`, `12=D`. On reverse state, immediately let the factory camera flow remain visible; the launcher must not draw a full-screen or touch-blocking layer over it.
- Use frame-963 output states for current lamp statuses; some frame-564 switch fields pulse or toggle during operation.
- The launcher product scope explicitly **excludes the entire seat-belt section**, even though driver-belt bits changed in the probe. Do not show a seat-belt widget. Do not show wipers/windows. Passenger-airbag on/off must remain accessible through the factory Car Settings UI; do not infer airbag health from unverified raw CAN fields. Speed remains hidden/uncalibrated until logged while moving. Odometer uses Appendix I’s factory 32-bit decoder and should be cross-checked against the cluster.
- Use the MCU service for reads at first. Do not add write-based volume, brightness, EQ or radio controls until a deliberately selected, reversible write is tested and verified by reading the value back. Do not assume `brightness`, `EQ1k` or `getfreq` keyed reads work because those failed in this probe.
- USB music/library reads can support a first media card. Keep factory music activity as a fallback for track selection. For video, launch the factory screen rather than assuming the video AIDL control service is ready.
- For Bluetooth, retain factory dialer/settings/music entry points and a coarse connection/call-state indicator. Add steering-wheel BT music handling only after the parked-car test in Appendix H passes. Do not claim launcher song metadata, cover art, touchscreen BT transport, direct call commands, contacts or call logs until a real external API is found and validated.

### G.7 Short, prioritized next-test list

1. Compare the corrected 34,984 km parser-derived odometer with the actual instrument-cluster reading; then capture speed while the car is moving, with a passenger operating/logging the probe (never operate the probe while driving).
2. Perform a reversible MCU write test (start with volume or another low-risk setting), read it back, and restore the original value. Treat each setting as separately verified.
3. Run the EasyConnected acquire/steering-key test in Appendix H while parked and while A2DP audio is actively playing from the phone. Test both transitions into BT source 3 and the already-source-3 case, then test call/focus loss and reconnect.
4. Retest the factory video Activity with a USB video; do not assume the current VideoService Binder is usable.
5. Obtain and decompile `net.easyconn.apk`; it is not in the currently accessible 26-file GitHub repository. Do not spend further time trying `zhbt_service` unless new evidence shows a valid service registration.

---

## Appendix H — Bluetooth phone music / GOC stack / steering-wheel route (source analysis 30 October 2026)

This appendix supersedes the old statement that the active Bluetooth backend is NForetek. The current factory BT app contains multiple backends, but the active firmware configuration selects GOC.

### H.1 Active backend selection — `[CODE]`

Relevant source paths in the supplied `sources.zip`:

- `sources/com.zhonghong.bluetooth/sources/com/zhonghong/bluetooth/application/BtApp.java`
- `.../BtSDK.java`
- `.../BlueToothManager.java`
- `.../ControllerFactroy.java`
- `.../impl_goc_java/GocJavaController.java`
- `.../impl_goc_java/GocJavaDataTranfser.java`
- `.../audiofocus/BtMusicAudioHelper.java`
- `.../audiofocus/BaseSourceHelper.java`
- `.../manager/BtMusicCtrlManager.java`
- `.../receiver/EasyConnectedReceiver.java`

`BtApp.onCreate()` invokes `BtSDK.Config(1).supportMultiNumber(true).build()`. `BlueToothManager.init(type=1)` creates `GocBluetoothAdapter`; `ControllerFactroy.init(1)` creates `GocJavaController`. Type 0 selects NForetek and type 2 selects IVT, but neither is the active selection in this `BtApp` startup path.

The GOC controller opens `new SerialPort(new File(mSerialName), 115200, 0)`. `mSerialName` is `"/dev/" + SystemProperties.get("ro.kernel.android.bt", "goc_serial")`; on this unit the property is `goc_serial`, hence `/dev/goc_serial` at 115200 baud. The factory BT process owns this serial I/O. **Do not open, read or write this device from the launcher**; competing access can steal bytes or break the original stack.

### H.2 GOC music commands and responses — `[CODE]`, not yet runtime-tested independently

| Purpose | GOC command / indication | Notes |
|---|---|---|
| Request current song metadata | `AT#MK` | `CMD_INQUIRY_MUSIC_INFO`; request only, does not start playback. |
| Play | `AT#MS` | `CMD_PLAY_MUSIC` |
| Pause | `AT#MJ` | `CMD_PAUSE_MUSIC` |
| Toggle play/pause | `AT#MA` | Declared as `CMD_PLAY_PAUSE_MUSIC`. |
| Next | `AT#MD` | `CMD_NEXT_SOUND` |
| Previous | `AT#ME` | `CMD_PREV_SOUND` |
| Stop | `AT#MC` | `CMD_STOP_MUSIC` |
| Mute / unmute music | `AT#VA` / `AT#VB` | Used by focus handling. |
| A2DP state | `MU` | Parser maps state to an A2DP connection state and reports whether the A2DP stream is active. |
| Music play/stop | `MB` / `MA` | Internal play state values: play indication maps to 0, stop indication maps to 2. |
| Song metadata | `MI` | Payload after prefix is split on `[FF]`; six fields are expected. |
| Track progress | `MP` | Parses four hex characters for position and four for duration. |

For `MI`, `GocJavaDataTranfser.processSongInfo()` expects six `[FF]`-separated fields and creates `BaseId3Info(album=infos[2], artist=infos[1], title=infos[0])`. The remaining fields include track/time/index/count metadata. `BaseId3Info` contains **album, artist and title only**; no cover-art field exists in this model. The parser and resulting `MusicManager` callbacks are inside the BT application process.

### H.3 Why the normal app cannot currently read BT metadata or call the controls — `[CODE]` + `[DEVICE]`

1. The BT manifest declares `BtCtrlManagerService` with action `com.zhonghong.bluetooth.START_BtCtrlService`, but this component's `onBind()` returns `null`.
2. The source class `com.zhonghong.bluetooth.service.BtService` contains the `IBtCtrlInterface` Binder and would register `zhbt_service`, but **`BtService` is not declared in the supplied BT manifest**. `BtSDK.initData()` attempts `startService(new Intent(context, BtService.class))` and catches/logs any exception. The real-device CarCheck did not find `zhbt_service` in ServiceManager.
3. The BT content provider `content://com.zhonghong.bluetooth.provider.btinfo/btmusic` exposes columns `title`, `artist`, `album`, `duration`, `btmusicstate`, but the probe returned null useful fields. The provider table is seeded with an empty row; source search did not find a functioning writer that publishes the GOC ID3 data into this provider. `btcontacts` was empty.
4. `com.car.phone_data` is exported in the phone-provider manifest, but the CarCheck's `/contacts` and `/call_log` queries returned null cursors. Manifest export alone is not proof of usable data.
5. `MyMediaButtonBroadcast` is not a reliable external route: it is not manifest-declared and no dependable dynamic registration was found.

Therefore: **internal BT capability is not equivalent to a public API for the launcher**. Do not build a custom BT track card, artwork, direct touchscreen transport or call-control UI against `zhbt_service` or the empty providers yet. Android `MediaSession`/`MediaBrowser` publication is not established by this source analysis.

### H.4 Steering-wheel transport route — promising `[CODE]`; real-car test pending

The strongest path found for the agreed Level-3 steering-wheel controls is the existing factory handshake with EasyConnected:

1. The BT manifest declares `EasyConnectedReceiver` with `android:exported="true"` and no permission, including action `net.easyconn.a2dp.acquire` (also lists `net.easyconn.a2dp.release`, `net.easyconn.bt.checkstatus`, `net.easyconn.bt.connected`, `net.easyconn.app.quit`, `net.easyconn.bt.connect`, `net.easyconn.connection.break`).
2. In the receiver source, the only A2DP transition with a specific action branch is `net.easyconn.a2dp.acquire`, which calls `BtMusicAudioHelper.getInstance().doEasyLinkEnter()`.
3. `doEasyLinkEnter()` unmutes BT music, sets `isLinkState=true`, and, **only if `getCurSource()!=3`**, calls `setSource()` and `registerSourceCallback()`. BT music Logic source ID is `3`.
4. `Logic.SourceHelper.setSourceId(3)` maps this Logic source to MCU source `13`. Do not confuse Logic source IDs with the `MCU_SOURCE` values read from MCU domain 3; they are different namespaces.
5. `LogicBinder` recognises media key codes including `85`, `86`, `87`, `88`, `126`, `127`. `SourceHelper.handleMediaKey()` requires Logic audio focus, then broadcasts `onMediaButtonPressed` with both the key code and current source ID to registered Logic source callbacks.
6. The BT `BaseSourceHelper` registers a source callback for source ID 3. `BtMusicCtrlManager` maps the received key codes as follows:

| Key code | BT music operation |
|---:|---|
| 85 | Toggle play/pause based on internal playing state |
| 86 | Stop |
| 87 | Next track |
| 88 | Previous track |
| 126 | Play |
| 127 | Pause |

`BtApp.onCreate()` registers `BtMusicCtrlManager` and `BtMusicAudioHelper` during startup, and the application is marked persistent in the BT manifest. Thus the callback target exists without intentionally opening `MusicActivity`.

**Caveats to test:**
- If the current Logic source is already `3` when `a2dp.acquire` arrives, `doEasyLinkEnter()` skips both `setSource()` and `registerSourceCallback()`. The callback may therefore not be registered in that already-source-3 case.
- The manifest lists `net.easyconn.a2dp.release`, but `EasyConnectedReceiver.onReceive()` has no explicit handling branch for it. Callback unregister/source cleanup on A2DP release is not established by that receiver.
- `getBluetoothConnectState()` and `Settings.System bt_connected` are coarse HFP/A2DP indicators. `ZServiceProviderTypes` declares A2DP type `2005`, but `BtCtrlManager.getBoolean(2005)` returns the default false because only other Bluetooth types are implemented. Do not use it as an A2DP-only status.
- The route is **static source analysis only**. No physical steering-wheel key was confirmed in this test run.

### H.5 Safe parked-car verification procedure

Use a passenger/technician for ADB/log capture. Do not operate ADB or a probe while driving.

1. Park safely. Connect the phone to the car Bluetooth and start music on the phone so A2DP is actively streaming. Keep the custom launcher visible.
2. Record the current Logic source/focus state and current playback state.
3. Send the same acquire action that EasyConnected is expected to send:

```bash
adb shell am broadcast -a net.easyconn.a2dp.acquire -p com.zhonghong.bluetooth
```

4. With the car stationary and the launcher still visible, press steering-wheel play/pause, next and previous. Verify that the phone's current track/playback changes and that the launcher does not leave the foreground.
5. Repeat twice: (a) first while source 3 is not active, so acquire must select it and register callbacks; (b) again when source 3 is already active, to expose the callback-registration caveat.
6. Test audio-focus loss/gain and a phone call, then end the call. The BT helper's source code mutes/pauses on focus loss and unmutes/resumes after focus gain when it itself paused playback. Confirm no unexpected permanent mute or stuck source.
7. Test EasyConnected disconnect/release and reconnect. Confirm the source and callback are cleaned up; do not assume the release receiver currently handles it.
8. Record logcat for tags `BtMusicSourceHelper`, `BtMusicCtrlManager`, `LogicBinder`, `SourceHelper`, `BT_Controller` and `BT_Parse`.

Do not repeatedly send source-changing broadcasts in a production launcher until the above lifecycle test has been completed. The acquire broadcast does not itself request song metadata, start music, or provide track commands to the launcher; it asks the factory BT app to take/prepare its BT music source.

### H.6 What is still needed to complete Level 3

- Obtain and decompile **`net.easyconn.apk`**; it is installed on the head unit but missing from the currently accessible GitHub repository. Inspect its A2DP acquire/release logic, connection detection and media-key handling.
- Determine whether the GOC parser's ID3 callback can be bridged by a supported exported component, an officially available broker callback, or another public API. Do not use private reflection into the BT process or serial-port sharing as the production design.
- Prove a safe touchscreen transport interface for play/pause/next/previous. The receiver currently supports source acquisition, not a command interface from a third-party app.
- Confirm whether any Android MediaSession exists while GOC A2DP music plays; this remains untested.
- Album artwork is not in the current `BaseId3Info` payload. A separate metadata/artwork source would be needed even after title/artist/album are bridged.

---

## Appendix I — Corrected 32-bit odometer decode and maintenance reminder calculation (30 October 2026)

The earlier CarCheck summary printed `53342` for both BCM and ICM. That value is only the low 16-bit half; it is **not** the full odometer and must not be divided by 1000 to display 53 km.

### I.1 Raw frames recorded

```text
RAW 800:  03 20 09 5E D0 15 02 00 00 00 00 00 76 0A
RAW 1219: 04 C3 09 5E D0 15 02 07 00 00 00 00 21 0A
```

Both contain the same odometer payload bytes `5E D0 15 02`.

### I.2 Factory parser and result

`com.zhonghong.canproxy.bean.CarSetInfo` parses BCM and ICM independently with the same 32-bit combination:

```java
ODO = getIntByBit(bytes, 0, 0, 16)
    + ((getIntByBit(bytes, 2, 0, 16) << 16) & 4294967295L);
```

The resulting little-endian integer is `0x0215D05E` = **34,984,030 raw units**. Both frames match. `MaintainServiceNewFragment.getKmMiles()` converts this value using `floor(raw / 1000.0)`, so the factory display calculation is **34,984 km**.

This is a code-and-raw-frame decode, not an independent visual comparison with the instrument cluster. Do a stationary cluster comparison before treating the figure as physically cross-checked. Do not use the earlier 53 km interpretation.

### I.3 Current maintenance reminders from CarCheck

The tested `Settings.System` values for all four reminder records were:

```text
CAR_ENGINE_OIL_CHANGE_REMAIN_TYPE       = 4000_33926
CAR_ENGINE_OIR_FILTER_CHANGE_REMAIN_TYPE = 4000_33926
CAR_ENGINE_AIR_FILTER_CHANGE_REMAIN_TYPE = 4000_33926
CAR_FUEL_FILTER_CHANGE_REMAIN_TYPE       = 4000_33926
```

The factory parser splits each value as `targetInterval_previousServiceOdometer`. Its formula is:

```text
remaining = targetInterval - (currentOdometerKm - previousServiceOdometerKm)
```

Using target interval 4,000 km, previous service marker 33,926 km, and decoded current odometer 34,984 km:

```text
4000 - (34984 - 33926) = 2942 km remaining
```

Thus the current factory-calculated reminder is **2,942 km remaining** for oil, oil filter, air filter and fuel filter. This reflects the values currently stored in Settings.System; confirm the service records/intervals with the owner before presenting them as confirmed maintenance history. The factory UI's `updateODO_calculated_By_BCM()` only logs BCM; ICM value is used for maintenance calculations.

---

## Appendix J — Agreed launcher feature matrix and implementation priorities

This is the product/implementation handoff, not proof that every feature is already accessible. The status in the right column takes precedence over an optimistic feature name elsewhere in this older technical document.

| Priority | Feature | Decision / next action |
|---|---|---|
| P0 | Native launcher shell | Build a normal third-party Kotlin launcher APK, 32-bit ARM compatible, Android 7.1.2/SDK 25. `targetSdkVersion 22` is preferred for the no-runtime-permission-UI environment. No root/system UID. |
| P0 | Screen/UI system | Arabic, 952×600 landscape. Multiple structurally different layouts (not colour-only themes); selectable colour themes as a separate layer; large controls and minimal driving clutter. |
| P0 | Reverse safety | On Logic/SystemState reverse callback, relinquish/avoid any full-screen overlay and let the factory reverse-camera flow appear. Never intercept the camera touch layer. |
| P0 | Doors/gear/lights/handbrake/brake | Use the verified CAN/Logic event paths and tested polarities. Use event callbacks and refresh reads; do not constantly poll. |
| P0 | Bluetooth Level 3 — steering keys | Implement only after Appendix H parked-car test passes. It should preserve launcher foreground while audio continues from the phone. Handle connection, source, focus and release lifecycle. |
| P1 | Odometer/maintenance | Use Appendix I's full 32-bit decode and factory km conversion; sanity-check odometer against instrument cluster. Present maintenance reminders only when their stored values are present and owner-confirmed. |
| P1 | TPMS / ambient temperature | Apply factory formulas and availability gating. Smooth minor ambient swings. Verify TPMS units and all wheel states on device. |
| P1 | Arabic voice alerts | Optional door-open alert using only validated door events; debounce/repeat suppression; setting to enable/disable. Arabic TTS availability must be checked on device. |
| P1 | Startup greeting | Optional greeting saying “مرحباً إسماعيل” or equivalent, with an explicit enable/disable setting; test voice output and avoid playing during calls/reverse alerts. |
| P1 | USB music | Build a media card around `UsbMusicService` and library provider, but test its controls before exposing active buttons. Provide factory-list fallback. |
| P1 | Navigation shortcuts/mix | Launch Neshan/Google Maps/Target. Read/test navigation mix settings. Never obscure reverse camera. |
| P1 | Passenger airbag | Preserve access to the factory passenger-airbag ON/OFF setting/status. Do not convert raw CAN cutoff/error fields into warnings until validated. |
| P2 | Phone contacts/call log/custom call overlay | Desired but blocked by current null/empty provider results and unavailable `zhbt_service`. Keep factory BT dialer UI as fallback. Revisit after new API evidence. |
| P2 | Bluetooth track metadata / artwork / touchscreen controls | Desired part of Level 3, but not solved. Need EasyConnected APK/API discovery or another supported external bridge. GOC internal ID3 is not enough. |
| P2 | Radio custom controls | Keep factory radio Activity until MCU writes/tuning are proven reversible and reliable. |
| P2 | Inline/PiP video | Not established; factory video Activity is the supported fallback at present. |
| Excluded | Seat-belt section | Do not implement or display a driver/passenger belt widget. This is an explicit product decision. |
| Excluded | Wiper/window widgets | Do not implement; no useful vehicle signals have been verified and the owner reports these sensors are unusable. |

### J.1 Implementation architecture guidance

- Separate modules/classes for `CanRepository`, `LogicStateRepository`, `McuReadRepository`, `SystemStateRepository`, `UsbMediaRepository`, `BluetoothIntegration`, navigation launchers and UI. Hardware callbacks should update a shared state model; UI must not call Binder synchronously on the main thread.
- Prefer callbacks + a controlled refresh/read after a CAN module notification. Unregister on service disconnect/activity shutdown. Add reconnect logic after Binder death.
- Keep reads separate from writes. MCU domain read-all and selected reads have been tested; writes and audio/radio mutations have not. Do not ship setting write controls until a reversible write/read-back/restore test passes for each key.
- Audio-source switching is controlled by Logic IDs. BT music is Logic source 3 and maps to MCU source 13; USB music is a different source. Never assume `MCU_SOURCE` equals the Logic source ID.
- Use System State/Logic notifications for ACC, reverse, illumination, volume and BT coarse state. Do not read serial ports directly.
- Avoid a custom incoming-call overlay until the factory UI behaviour and call callbacks are well understood. If the factory call screen appears, let it own call interaction.

---

## Appendix K — Repository and source-provenance audit (30 October 2026)

The public repository `https://github.com/IsmailAabedah/forDel` was accessed through the GitHub connection. Its `main` tree at the inspected revision contained **26 files, all APKs**, with a total reported size of approximately 49.1 MB. Relevant packages include `com.zhonghong.bluetooth`, `com.zhonghong.logic`, `com.zhonghong.mcu`, `com.zhonghong.aidlserver`, `com.zhonghong.yzmusic`, `com.zhonghong.yzvideo`, `com.zhonghong.can`, `com.zhonghong.carsetting`, `com.zhonghong.launcher` and `com.car.phoneprovider`.

**Missing from that repository:** `net.easyconn.apk`, `framework.jar`, `services.jar`, and `framework-res.apk`. `net.easyconn` is listed as installed on the head unit, but its source was not present in `sources.zip` and its APK is the next most valuable file for this work.

The GitHub connector exposed the tree and smaller APKs, but did not return the full binary payload of the 4.89 MB `com.zhonghong.bluetooth.apk`; the detailed Java analysis of the BT code in Appendix H is therefore based on the previously supplied and extracted `sources.zip` plus `all_manifests.zip`, not a claim that the large GitHub binary was independently downloaded and re-decompiled in this session. The repository audit is a file inventory, not an assertion that all 26 APKs were decompiled and read class-by-class. The deepest code review to date focuses on Bluetooth, Logic, MCU, CAN, AIDL broker, media, video and phone-provider paths.

### K.1 Next files to obtain

1. `net.easyconn.apk` — highest priority, specifically to inspect A2DP acquire/release and media-key broadcast behaviour.
2. `framework.jar`, `services.jar`, `framework-res.apk` — useful only if later work requires framework-level media-key, MediaSession or hidden service verification.
3. If more runtime evidence is needed: a parked-car CarCheck log during active phone A2DP playback with steering-wheel play/pause/next/previous pressed, including logcat tags listed in Appendix H.5. Do not collect/operate logs while driving.


---

## Appendix L — EasyConnected (`net.easyconn`) integration analysis (31 October 2026)

Source: the jadx output of `net.easyconn.apk` supplied by the owner (2,890 Java files plus the decoded manifest). Everything here is `[CODE]`; nothing was exercised on the unit.

### L.1 What it is and what it is not
EasyConnected is the phone-mirroring app (Android/iOS link; the factory launcher and Logic treat it as the "EasyLink/CarLife" app: `net.easyconn/net.easyconn.ui.Xzc01MainActivity`). Its whole protocol with a head unit is **plain broadcasts**. A search of its sources finds **no reference to any ZhongHong interface** (`com.zhonghong`, `android.zhonghong`, `com.logic`, MCU, CAN, `zhbt_service`). It therefore gives **no extra access** to the MCU, CAN, Logic or Bluetooth control, and it does **not** solve the missing `zhbt_service`. What it adds is (a) a documented broadcast protocol a launcher can speak, (b) phone-link state and media/navigation information while a phone is linked, and (c) the A2DP hand-off that the Bluetooth app already answers.

### L.2 Manifest surface (exported / reachable by other apps)
| Component | Exposure | Meaning |
|---|---|---|
| `EcShortcutBroadcastReceiver` | exported, action `net.easyconn.start.ec`, int extra `ec_app_type` | start EasyConnected and open a page (L.3) |
| `EcConnectStateReceiver` | exported, actions `net.easyconn.connect.checkstatus` and `net.easyconn.link.checkstatus` | state query. Replies are broadcasts: `net.easyconn.mirror.in` / `net.easyconn.mirror.out` and `net.easyconn.link.in` / `net.easyconn.link.out` |
| `EasyConnectProxyService` | exported, action `net.easyconn.proxy.service`, **Messenger** based | registration / licence / QR / UUID helper. **Do not use**: it handles identifiers and licensing, not control |
| `MediaRenderService` | exported, action `com.carbit.ios` | iOS (AirPlay-style) renderer. Not a control surface |
| `NetLinkService`, `UsbIntentReceiver`, others | internal | VPN link and USB attach handling |
| Activities | `net.easyconn.ui.Xzc01MainActivity`, action `net.easyconn6.mainactivity` | launch targets |

### L.3 Pages for `net.easyconn.start.ec` (int extra `ec_app_type`)
1 navigation, 2 music, 3 voice assistant (VR), 4 talkie, 5 navigation-home, 6 navigation-work, 7 main, 8 gas stations, 9 car parks, 10 4S shop, 11 music-XMLY, 12 music-QQ, 13 music-radio, 14 music-native, 15 WeChat, 16 personal centre, 17 app manager, 18 add/OTA, 19 WeChat messages.

### L.4 Broadcast catalogue
**Inbound (what EasyConnected listens for; a head unit sends these):**
- `net.easyconn.BACKING_UP_START` / `net.easyconn.BACKING_UP_STOP` (reverse gear)
- `net.easyconn.navi.daytime` / `net.easyconn.navi.night` (day/night)
- `net.easyconn.drivemode.opened` / `net.easyconn.drivemode.closed`
- `net.easyconn.bt.connected` (String[] extra `name`: connected Bluetooth device names), `net.easyconn.bt.opened`, `net.easyconn.bt.closed`
- `net.easyconn.start.vr` / `net.easyconn.stop.vr`, `net.easyconn.navigation.STOP_EASYCONN_NAVI`, `net.easyconn.mediacodec.acquire` / `release`
- `net.easyconn.KEYCODE` with int extras `ECBtnCode` and `ECBtnEventType` (key forwarding to the phone app)
- `android.intent.action.MEDIA_BUTTON` (KeyEvent 85 play/pause, 87 next, 88 previous, 126 play, 127 pause) — acted on only while EasyConnected's own music audio is active
**Outbound (what EasyConnected sends; a launcher can listen):**
- `net.easyconn.a2dp.acquire` and `net.easyconn.a2dp.release` (to the Bluetooth app), `net.easyconn.bt.checkstatus`, `net.easyconn.bluetooth.call`, `net.easyconn.bt.setting`
- `net.easyconn.music.status` with Bundle extra `ec_music_info`: `status` (int), `title`, `artist`, `album`, `albumArtist`, `length` (long). **Only for music played inside the linked phone app, not for generic A2DP**; no artwork field
- `net.easyconn.audiofocus.music.status` (int `status`)
- `net.easyconn.navigation.status` (navigation HUD of the linked phone: `cameraSpeed`, `cameraDistance`, `naviIcon`, `nextRoad`, `roadRemainingDistance`, `roadRemainingTime`, `destinationRemainingDistance`, `destinationRemainingTime`, `arriveTime`, `arriveTimeZone`, car direction), `net.easyconn.navigation.STARTED` / `STOPPED`
- link state: `net.easyconn.link.in/out`, `net.easyconn.mirror.in/out/pause/resume`, `net.easyconn.app.open.state`, `net.easyconn.app.open/pause/resume/quit`
- phone data: `net.easyconn.weather.info`, `net.easyconn.gps`, `net.easyconn.phone.time`, `net.easyconn.vr.status`, `net.easyconn.vr.text`, `net.easyconn.vr.tips`, `net.easyconn.carcontrol.request/cmd/response`
**Button codes** (`ECBtnCode`): music play 4160, next 4161, previous 4162, pause 4163, stop 4164, play/pause 4167; volume up 4176, down 4177; corner buttons 4192-4195; mode 4208; app front 4224; app back 4240; force landscape 4256, cancel landscape 4257, toggle landscape 4258; talkie group mute 4113, cancel mute 4114, toggle mute 4115. **Event types** (`ECBtnEventType`): 0 up, 1 down, 2 click, 3 double-click, 4 long-press.

### L.5 Factory side of the handshake `[CODE]`
The factory Bluetooth app contains the receiver for `net.easyconn.a2dp.acquire` and `net.easyconn.bt.checkstatus`. It answers with `net.easyconn.a2dp.acquire.ok` / `.fail`, `net.easyconn.a2dp.release.ok` / `.fail`, and `net.easyconn.bt.connected` / `.opened` / `.closed`. The factory launcher and Logic only **launch** EasyConnected and treat its package as the CarLife/EasyLink source; **no factory app sends** `BACKING_UP_*`, `navi.*`, `drivemode.*` or `net.easyconn.KEYCODE` to it. On this unit EasyConnected therefore does not hear about reverse or day/night unless something else sends it; a launcher may send them `[UNTESTED]`.

### L.6 Consequences for the launcher
1. **Level-3 steering-wheel control of Bluetooth music** stays exactly as Appendix H describes (A2DP hand-off). EasyConnected provides **no AVRCP track-metadata API** for plain Bluetooth. Track metadata exists only through `net.easyconn.music.status` while the EasyConnected phone app is linked. Otherwise show only the connected device and play state.
2. Phone-link state (phone linked / mirroring) is readable through the check-status protocol; a launcher can show it and open EasyConnected pages with `net.easyconn.start.ec`.
3. Navigation of the linked phone can be mirrored into a launcher HUD card from `net.easyconn.navigation.status`.
4. Never call `EasyConnectProxyService`; never forward `MEDIA_BUTTON` into EasyConnected unless the user is using its music.
5. Sending `BACKING_UP_START/STOP` and day/night to EasyConnected is optional and `[UNTESTED]`: do it only if a bug in phone-link behaviour is observed.

---

## Appendix M — Complete control matrix with exact payloads (revision 3)

Reading guide: **R** = read path, **W** = write/command path. Status uses the document legend: `[VERIFIED]` on the unit, `[CODE]` read in factory sources only, `[NOT AVAILABLE]` proven absent. Every `[CODE]` write is **untested**: ship it behind the reversible self-test in M.3.

### M.1 Wire summary (so nothing has to be rediscovered)
- **MCU write**: `sendInfo(domain, 1, p)` where `p` holds `writeByteArray(jsonBytes)` (transaction 4 on `zhonghong.mcu`; descriptor `android.zhonghong.IMcuService`; request = token, `writeInt(domain)`, `writeInt(1)`, then the raw data of `p`; reply = a single int, 1 = ok).
- **MCU read-all**: `obtainInfo(domain, 1, in, out)` (transaction 3) with `in = writeByteArray("{\"all\":0}")`; reply = `out` data followed by a final int; `out` = `int len, int type(=1), len bytes` and the text is the first `len-1` bytes. **Keyed read**: same with `{"KEY":0}`. `[VERIFIED]`
- **Logic**: standard AIDL on `_logic_service`, descriptor `com.logic.common.aidl.ILogicInterface`: 3 `setCmd(String,String,String)`, 4 `getValue(String,String)`. `[VERIFIED]` for `getValue`; `setCmd` `[CODE]`.

### M.2 Capability table
| # | Capability | R (read) | W (write / command) | Status |
|---|---|---|---|---|
| 1 | Doors, lamps, brake pedal, hand brake, driver belt (not shown by decision), gear lever, central-lock pulses, TPMS, ambient temperature, power relays, airbag status | CAN service (section 1b-A, Appendix G) | none (CAN is read-only on this unit) | `[VERIFIED]` |
| 2 | Odometer, maintenance reminders | CAN frames 800/1219 (32-bit decode, Appendix I) + `Settings.System CAR_*_REMAIN_TYPE` | none | `[CODE]` + values read `[VERIFIED]` |
| 3 | ACC, power, running, lights-switch state, reverse | Logic `getValue` + events; MCU domain 3; broker callbacks; `Settings.System back_state` | none | `[VERIFIED]` (four redundant sources) |
| 4 | Master volume | MCU `SYS_VOL`; broker `notifyVOL` | MCU domain 2 `{"SYS_VOL": n}` | R `[VERIFIED]`, W `[CODE]` |
| 5 | Mute | MCU `SYS_MUTE` | `{"SYS_MUTE": 0|1}`; `{"FORCE_MUTE": n}` | R `[VERIFIED]`, W `[CODE]` |
| 6 | Tone and sound field | `treb`, `SYS_MID`, `bass`, `audiobal`, `audiofade`, `SYS_LOUD`, `SYS_AUDIOMODE`, `3D_AUDIO_SW`, `carspeed_volume`, `SYS_BEEP`, `SYS_BEEPVOL`, `SYS_BTVOL`, `SYS_TTSVOL`, `SYS_GPSVOL` (all present in the domain-2 JSON) | the same key names with `{"KEY": value}` (`SYS_LOUD`, `SYS_BEEP`, `3D_AUDIO_SW`: 0/1). The factory EQ screen shows allowed ranges (`SoundManager`): read the current value first and stay inside the range the factory UI offers | R `[VERIFIED]`, W `[CODE]` |
| 7 | Screen brightness | `Settings.System screen_brightness` (observed 230); MCU `backlight` (observed 80); `setautobrighton` | factory path: `Settings.System.putInt(screen_brightness, v)` with the five factory levels 70 / 110 / 150 / 190 / 230; day/night behaviour is kept in `Settings.System` keys (`light_mode_value`, `save_hand_mode_value`, `mode_day_value`, `mode_night_value`, `mode_day_time`, `mode_night_time`) and applied by the broadcast `com.zhonghong.action.ill` (`SetService.ACTION_ILL_STATE_CHANGE`; auto mode value 1, manual 0). Needs `WRITE_SETTINGS` (granted at install with `targetSdkVersion 22`) | R `[VERIFIED]`, W `[CODE]` |
| 8 | Active audio source and focus | Logic `getSourceId`, `getThirdSourceId`, `hasFocusById`, `getFocusStateById`, `getFocusState`; MCU `MCU_SOURCE` | Logic `setCmd("setSourceId", id)`, `setCmd("releaseSourceId", id)`, `setCmd("closeSourceId", id)`. Ids: radio 0, USB music 1, video 2, BT music 3, online music 4, SD music 9, SD video 10, AUX 11, CarLife 12, BT phone 13, third-party audio 15. **Logic id and MCU source value are different numbering** (BT music = Logic 3 = MCU 13) | R `[VERIFIED]`, W `[CODE]` |
| 9 | Radio | MCU domain 1 `{"all":0}`: keys `getfreq`, `freq`, `scan`, `loc`, `st`, `stind`, `aps`, `RADIO_AREA`, `RADIO_PLAYSTATE`, `RADIO_SREACH_STATE`, `HAVESIG`, `signlevel`, `LAGEL_FREG`, RDS (`RADIO_NAME`, `RADIO_TEXT`, `PTY`, `TP`, `TP_INFO`, `TA_MODE`, `RDS_ON_OFF`, `RDS_TA_MODE`, `AF`, `EON`, `INTRO`). With the radio off the JSON is only `class`, `status:"off"`, `HAVESIG`, `RADIO_AREA` | Sequence `[CODE]`: (1) Logic `setSourceId 0`; (2) domain 1 `{"RADIO_PLAYSTATE": 0}` = play, `1` = pause (the factory sends `!play ? 1 : 0`); (3) tune: domain 1 `{"freq": (band << 14) | freq}` with **band 0 = FM, band 3 = AM**; FM units 10 kHz (8750-10800, step 10), AM kHz (522-1620, step 9); (4) seek: **domain 4** `{"RADIO_SREACH_R":0}` or `{"RADIO_SREACH_L":0}`; (5) `{"scan":0}`, `{"stop_scan":0}` on domain 1. Area ids: 0 Europe, 1 USA, 3 Japan, 4 Russia, 5 Asia, 6 South-USA (unit reports 0). Presets are stored in the factory radio app's private database: keep the launcher's own preset list | R off-state `[VERIFIED]`, rest `[CODE]` |
| 10 | USB music | `UsbMusicService`: `isPlaying`, `getTitle`, `getArtist`, `getAlbum` + listener `onId3`/`onPlayState`; library: `content://com.zhonghong.media/audio` and `id3` | `UsbMusicService` codes 7 `setMusicSource` (switch source first), 8 next, 9 prev, 10 playPause, 11 play, 12 pause. No "play this file": open `com.zhonghong.usb_music_list` | bind/reads `[VERIFIED]`, commands `[CODE]` |
| 11 | USB video, pictures, AUX | launch only | actions in section 3.8 (`com.zhonghong.video_activity`, `com.zhonghong.usb_music_activity`, AUX `com.zh.auxin`) | `[VERIFIED]` resolvable; video service `[NOT AVAILABLE]` |
| 12 | Bluetooth state | `Settings.System bt_connected / key_bluetooth_state / key_signal_state / key_battery_state`, `sys.btcall`, broker `getBluetoothConnectState`, `getBluetoothInCallState`, `notifyBTPhoneState` | none | `[VERIFIED]` |
| 13 | Calls: answer / reject / dial / contacts / call log | factory UI only | `zhbt_service` absent; dial only by typing in `android.bluetooth_btdial` | `[NOT AVAILABLE]` |
| 14 | Bluetooth music steering keys / A2DP metadata | Appendix H and L | A2DP hand-off per Appendix H | design in Appendix H, **no metadata API** (L.6) |
| 15 | Steering wheel and panel keys | three channels (M.4) | n/a | `[CODE]` |
| 16 | Screen off / wake | `isRunning`, ACC state | broadcast `com.zhonghong.logic.close_screen` (display off); touch wakes; `action.radio.RDS_TRAFFIC` wakes for traffic news; screensaver service `com.zhonghong.screensaver.ScreenService` | `[CODE]` untested |
| 17 | Beep | MCU `SYS_BEEP`, `SYS_BEEPVOL` | broadcast `com.zhonghong.android.key` with int extra `data = 4608`; MCU `{"SYS_BEEP":1}` | `[CODE]`, result unreported |
| 18 | Navigation apps | `zh.navi.pidlist`, `sys.innavi` (reserved), `Settings.System navi_mix_value` (default 10, music level kept under navigation voice) | launch Neshan / Google Maps / Target; `navi_mix_value` write `[CODE]`; custom navigation stream `STREAM_NAVI = 10` (max 30) | launch `[VERIFIED]` (apps installed) |
| 19 | Phone link | check-status broadcasts (Appendix L) | `net.easyconn.start.ec` + `ec_app_type` | `[CODE]` |
| 20 | Draw over other apps | n/a | `WindowManager` type 2002 with `SYSTEM_ALERT_WINDOW` | `[VERIFIED]` |
| 21 | Day/night | Logic illumination, CAN `Daylight_Status`, time of day | Launcher-own theme only; `net.easyconn.navi.daytime/night` optional for EasyConnected | `[VERIFIED]` inputs |
| 22 | Clock | MCU `SYS_YEAR` ... `SYS_SECOND`, `SYS_TIME12H`; `Settings.System time_12_24`; `Settings.Global auto_time*` | not needed | read `[VERIFIED]` |
| 23 | Factory screens | n/a | section 3.8 (settings, EQ, car settings, update, factory test) | `[VERIFIED]` resolvable |
| 24 | Do-not-touch | MCU update, system update, factory test, `EasyConnectProxyService`, raw `ttyAMA*` ports | never | rule |

### M.3 Safe self-test the launcher should run before exposing any `[CODE]` write
Run only with the car parked and the user's confirmation; log every result; if any step fails, keep the control hidden and use the fallback.
1. **Volume/EQ keys**: read the current value, write the **same** value, read back; then change by one step, read back, restore the original.
2. **Mute**: read, write `SYS_MUTE=1`, read back, write the original value immediately.
3. **Brightness**: read `screen_brightness`, write the same level, then the adjacent level, restore.
4. **Source switch**: only when nothing is playing: `getSourceId`, `setCmd setSourceId 1` (USB music), read back, restore the previous id.
5. **Radio**: only after the user enables it: step 1 of M.2 row 9, then read domain 1 and **capture the real JSON** (frequency keys were never captured with the radio on).
6. **USB music**: with a USB stick playing, call `playPause` twice and verify `onPlayState`.
7. **Wheel broadcast**: register a receiver for `com.zhonghong.zuiserver.BROADCAST` and log `WHEEL_KEY` values while the owner presses each steering-wheel key once (M.4 channel b).
Success criteria: read-back equals the written value within one step; no exception; the factory UI shows the same state.

### M.4 Steering-wheel and hard keys: three channels `[CODE]`
(a) **Media keys through Logic**: Android media keycodes 85, 86, 87, 88, 126, 127 are routed to the *active audio source's* registered `ILogicCallback.onMediaButtonPressed`. Works only while the launcher holds the audio source (Logic `setSourceId`) and registered a source callback.
(b) **Broadcast from Logic**: action `com.zhonghong.zuiserver.BROADCAST`, string extra `WHEEL_KEY`. Values seen in the code: `AMS` (key `SEL_DEC` 182), `SEL_RIGHT` (`SEL_INC` 183), `FMAM` (radio key 60/64), `APS` (72), `SCAN` (80), `ENTER` (37), `DPAD_UP` (44), `DIAL` (pick-up 93, dial 95), `HANG_UP` (hang-up 92, dial toggle), `VOICE` (voice key path). A dynamic receiver in the launcher can read these without any binder. Not yet seen on the unit.
(c) **MCU hard-key listener** (domain 4, `McuHardKeyInfo`): raw codes: VOL_INC 23, VOL_DEC 24, MUTE 15, MODE 13, POWER 12, PREV 19, NEXT 20, PLAY_PAUSE 17, PLAY 49, PAUSE 48, FF 21, REW 22, RADIO 60, GPS 120, GPS_NAVI 126, USB 107, DIAL 95, HANG_UP 92, PICK_UP 93, BACK_CALL 169, BACK 171, HOME 172, MENU 173, SCREENSAVE 174, EQ 28, TFT_LAMP 117, TFT_RESUME 84, TFTOFF 1003, ONE_KEY_SAVE 181, SEL_DEC 182, SEL_INC 183, ENTER 37, UP 44, DOWN 45, LEFT 46, RIGHT 47. Factory reactions: USB 107 opens home media; GPS 120 launches navigation (or sends HOME when navigation is on top); keys 171-174/178 only beep (the screen saver toggles on 174); the call-last-number code 231 kills the navigation process and redials; volume keys are handled inside the MCU. The `Instrumentation.sendKeyDownUpSync` helper in Logic injects HOME (3) and BACK (4) for the GPS key.

### M.5 What this system does **not** offer (so nothing stays ambiguous)
CAN control of the car (no sender exists); Bluetooth call control from a custom UI; contacts and call history; USB-video control; wiper, window and passenger-belt data; a six-band equalizer and a `brightness` MCU key; servers for the radio, BT-music, key, multi-screen, navigation and extra-audio-device AIDL interfaces; the AVM/CarLife/power/phone-link apps; any ZhongHong API inside EasyConnected.

---

## Appendix N — Corrections and resolved questions (revision 3)

| Item | Earlier statement | Verified correction / resolution |
|---|---|---|
| Radio band code | "band 3 = FM, 0 = AM" | The factory `RadioSdk.playFreq` sends `isAMStation(freq) ? 3 : 0`, so **0 = FM, 3 = AM**. Ranges FM 8750-10800 (step 10), AM 522-1620 (step 9) |
| EQ | "six-band EQ40..EQ16k" | Not present in the unit's JSON; only `bass`, `SYS_MID`, `treb` (plus `SYS_USER_*`) |
| Radio level key | `signlevel` and `HAVESIG` | Confirmed names: `signlevel`, `HAVESIG`, `LAGEL_FREG` |
| Odometer | "53342" | Superseded by Appendix I: the full 32-bit value is 34,984,030 raw (34,984 km by the factory conversion) |
| EasyConnected "API discovery" (Appendix J, P2) | open | Resolved in Appendix L: broadcast protocol only, no ZhongHong API, no AVRCP metadata |
| Steering-wheel signals | open | Three channels documented in M.4; none yet observed on the unit |
| Hard keys BACK/HOME/MENU | assumed to inject Android keys | In Logic they only beep; injection exists only for the GPS key (HOME/BACK) |
| Brightness write | unknown | Factory path is `Settings.System screen_brightness` (five levels) plus day/night keys and an apply broadcast (M.2 row 7) |
| Passenger airbag | "control" | Status only in the factory SRS page; no control path in code |
