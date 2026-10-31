# ZhongHong Diagnostic Probe

Standalone, read-only-first diagnostic app for the Saipa Quick / ZhongHong Android 7.1.2 head unit.

## Build and install
GitHub Actions workflow: **Build Car Diagnostic Probe APK**. It builds `app-debug.apk` and uploads it as the artifact `ZhongHong-Diagnostic-Probe-APK`.

**Important:** GitHub Actions downloads the artifact as a ZIP archive. Extract the ZIP first, then install the file named `app-debug.apk`. Do not try to install the ZIP itself. The APK is configured for legacy Android compatibility (minSdk 21, targetSdk 22, Java 8 bytecode and v1 APK signing).

## What it records
- Dynamic broadcast receiver: `com.zhonghong.zuiserver.BROADCAST` / `WHEEL_KEY`.
- Logic Binder callback: `onEventChanged`, including `onMediaButtonPressed` and `onKeyEvent`.
- MCU domain-4 listener registration attempt and raw callback parcel bytes.
- Bluetooth-related Settings.System values and Android music-active state.
- USB music service binding reachability only. No transport commands are sent.
- Read-only CAN service connection and snapshots for modules 1–4, including raw frame 1218 data. The known selector values are labelled P=9, R=10, N=11, D=12; any other value is logged as unclassified for later physical correlation.

## Requested investigations and known limits
- Sport / S / manual + and -: the app logs the raw shifter signal and related frame data. The available evidence does not yet establish the exact raw values for Sport or manual up/down, so the app must not label unknown values as Sport or a gear command until they are correlated with a safe, stationary test.
- 12V cigarette-lighter socket: the head-unit interfaces and CAN signals documented so far expose no confirmed socket-current or accessory-insertion sensor. Android cannot detect a generic charger or tool merely because it is plugged into the car's 12V socket. The probe can record CAN snapshots, but detection is not guaranteed; a hardware current/voltage sensor or an ECU-reported signal would be needed for reliable detection.

## Safety
The app does not write CAN, MCU settings, audio source, volume, or media transport commands. Test only while parked, with the parking brake applied. Do not test selector changes or accessories while driving. A successful registration/bind is not proof that the callback will deliver events; the log distinguishes registration from observed callbacks.
