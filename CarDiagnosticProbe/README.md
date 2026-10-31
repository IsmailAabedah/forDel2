# ZhongHong Diagnostic Probe

Standalone, read-only-first diagnostic app for the Saipa Quick / ZhongHong Android 7.1.2 head unit.

## Build
GitHub Actions workflow: **Build Car Diagnostic Probe APK**. It builds `app-debug.apk` and uploads it as an artifact.

## What it records
- Dynamic broadcast receiver: `com.zhonghong.zuiserver.BROADCAST` / `WHEEL_KEY`.
- Logic Binder callback: `onEventChanged`, including `onMediaButtonPressed` and `onKeyEvent`.
- MCU domain-4 listener registration attempt and raw callback parcel bytes.
- Bluetooth-related Settings.System values and Android music-active state.
- USB music service binding reachability only. No transport commands are sent.

## Safety
The app does not write CAN, MCU settings, audio source, volume, or media transport commands. Test only while parked. A successful registration/bind is not proof that the callback will deliver events; the log distinguishes registration from observed callbacks.
