# Desktop Standby Clock

[English](README.md) | [简体中文](README.zh-CN.md)

A native fullscreen Android clock for turning a spare landscape phone into a
dedicated desktop display.

<p align="center">
  <img src="docs/images/phosphor-dial.png" width="49%" alt="P3 amber phosphor dial">
  <img src="docs/images/calligraphy.png" width="49%" alt="Calligraphic digital clock">
</p>

## Highlights

- Two clock faces: a warm P3 amber phosphor dial and a restrained calligraphic display
- Automatic hourly face rotation, plus manual swipe switching
- Ambient-light blackout: dark room means pure black OLED pixels; light restores the clock and keeps it visible
- Tap-to-wake: a touch lights the blacked-out screen at low brightness for twenty seconds, so a dark room no longer means reaching for the wall switch
- Five-minute pixel shifting for OLED burn-in protection
- Persistent bedtime reminder with a short CRT-style chime
- Local controls for a Yeelight ceiling light and Wake-on-LAN desktop startup
- HostVDS and Sanmao remaining-traffic status from a private VPS bridge
- Dedicated-display recovery after boot or task removal on a rooted phone

## Controls

- Swipe up or down: change clock face
- Swipe right: open device controls
- Swipe left: open the HostVDS and Sanmao traffic dashboard
- Long press: open clock settings
- Tap while blacked out: light the screen for twenty seconds; any further touch refills the window, and the usual swipes work throughout

## Build

Requires JDK 17 and Android SDK Platform 35.

Copy the tracked template and fill in your own LAN devices and VPS endpoint:

```bash
cp standby-clock.properties.example standby-clock.properties
```

The local file is ignored by Git. A checkout without it still builds; device
controls and the traffic dashboard show `NOT CONFIGURED` until values are supplied.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Release signing and publication are documented in
[docs/RELEASING.md](docs/RELEASING.md).

## Documentation

Architecture, ambient-light thresholds, persistence, installation, verification,
traffic-bridge setup, and troubleshooting are documented in
[docs/MAINTENANCE.md](docs/MAINTENANCE.md).
