# Desktop Standby Clock

A native fullscreen Android clock for a dedicated landscape phone. It combines
ambient-aware OLED blackout, persistent desk-display behavior, switchable clock
faces, bedtime reminders, and trusted-LAN device controls.

## Android app

The Android version is a native app designed for a permanently landscape, rooted phone display. It includes:

- Two switchable clock faces: the P3 amber phosphor dial and a calligraphic digital face
- A fullscreen superellipse analog dial with 60 radial marks and numeral-free cardinal positions
- Sharp gradient hour and minute hands, a fine counterweighted second hand, and layered phosphor bloom
- English-only right-side information line with weekday, day, and 24-hour time (`WED 5   19:51`)
- Subtle CRT scanlines over the completed clock frame
- OLED-friendly pure black rendering
- P3 phosphor-inspired amber (`#FFB000`) with warm-orange glow
- Immersive landscape mode with display-cutout support
- A single persistent clock task with seamless rotation and no activity transition animation
- One-second hand updates and subtle pixel shifting
- Low-power ambient-light automation using the phone's light sensor
- OLED blackout after sustained darkness: pure black pixels and minimum window brightness without system sleep
- A daily bedtime reminder with a short CRT chime, persistent until `DONE`, and a `+15 MIN` delay option
- Boot and task-removal recovery for dedicated-display use
- Local-only network control for a Yeelight ceiling light and Wake-on-LAN desktop startup
- No camera or storage permission, and no image capture or machine-learning model

### Clock styles

Swipe up on the normal clock face for the next style or swipe down for the previous
style. The gesture is disabled while blackout, settings, or the bedtime
reminder is active, so the existing tap and long-press actions remain unambiguous.
You can also long-press the clock and use `[<]` / `[>]` on the `CLOCK STYLE` row.
Style changes use a 380 ms direction-aware slide and cross-fade: the old face exits in
the swipe direction while the new face enters from the opposite edge. Repeated style
changes are ignored until that short transition finishes.

`AUTO SWITCH` is enabled by default and advances to the next style one hour after the
last manual or automatic change. A manual swipe or menu change restarts the full
one-hour countdown. The enabled state, selected face, and next deadline are stored in
private app preferences; after a restart, an overdue deadline advances once and begins
a fresh hour. Set `AUTO SWITCH` to `OFF` in `CLOCK SETTINGS` to keep one face fixed.

- `PHOSPHOR DIAL` — the default P3 amber analog dial with CRT scanlines.
- `CALLIGRAPHY` — a digital face set in Cormorant Light Italic: warm off-white `#E8E0D0`
  hours and minutes, dimmer seconds with a `SEC` unit label, a hairline rule, and a
  letter-spaced date line in Cormorant Italic. Each digit slides and cross-fades over
  450 ms (350 ms for seconds) on a
  `cubic-bezier(0.4, 0, 0.15, 1)` curve, and the colon fades between its two states every
  second. Scanlines are omitted, since this face is not a raster display.

Both faces keep the five-minute pixel shift for OLED burn-in protection, and ambient-light
blackout and the bedtime reminder are unchanged by the style choice. The
overlays stay in the CRT amber theme regardless of the face.

### LAN device control

Swipe left or right on the normal clock face to open the two-button `DEVICE CONTROL`
panel. The left button queries and toggles the Yeelight ceiling light over its local TCP
protocol. The right button sends three Wake-on-LAN magic packets to the desktop. Swipe
again, or tap outside the two buttons, to return to the clock.

The 380 ms transition follows the gesture direction: the outgoing clock or panel slides
with the swipe while the incoming layer enters from the opposite side, using the same
easing curve as clock-style changes. Button brackets give a short CRT glow pulse, and
network status changes cross-fade vertically instead of snapping between labels.

The panel is unavailable during blackout, settings, and an active bedtime
reminder. While it is open, ambient-light blackout is held off so a network timeout cannot
make the controls disappear. Network work runs outside the UI thread; the displayed
states distinguish a packet being sent from the desktop actually completing startup.

The fixed trusted-LAN targets are kept in
[`DeviceControlConfig.java`](app/src/main/java/com/henry/standbyclock/DeviceControlConfig.java):

- Yeelight: `192.168.1.10:55443`, using developer-mode LAN control without a MiIO token.
- Desktop Wake-on-LAN: MAC `8C:32:23:49:15:3F`, broadcast to `192.168.1.255:9`.

The phone must be on the same LAN without Wi-Fi client isolation. Shutdown wake also
requires the desktop NIC and BIOS/UEFI Wake-on-LAN options to remain enabled.

The calligraphic face carries the reference design's very faint warm vignette
(3% alpha); drop the `drawStageHalo` call in
[`ClockView.java`](app/src/main/java/com/henry/standbyclock/ClockView.java) for strictly
pure-black rendering.

### Ambient-light automation

The clock stays visible indefinitely while the measured room light is bright. A reading
at or below `3 lux` must remain dark for 20 seconds before the clock blackouts. Once
blacked out, a reading at or above `15 lux` must remain bright for 3 seconds before the
clock returns, after which it remains visible until the room becomes dark again. Values
between the two thresholds preserve the current display state, preventing flicker near a
single cutoff. Brief shadows and a hand passing over the sensor do not toggle the display.

The phone remains logically awake in blackout mode. This keeps the activity in
landscape and avoids MIUI's portrait AOD-to-landscape wake transition. Nothing is
drawn while blacked out, so OLED pixels do not remain lit; the foreground service and
light sensor remain active. If a device has no ambient-light sensor, the safe fallback
is to keep the clock visible.

The app bundles four typefaces from Google Fonts, all under the SIL Open Font License
1.1, with their license texts stored in `third_party_licenses/`:

| Typeface | Used by | License file |
| --- | --- | --- |
| VT323 | CRT dial, settings, and reminder overlays | `VT323-OFL.txt` |
| Cormorant Light Italic | Calligraphic hours, minutes, and seconds | `Cormorant-OFL.txt` |
| Cormorant Italic | Calligraphic date line | `Cormorant-OFL.txt` |
| JetBrains Mono Light | Calligraphic `SEC` unit label | `JetBrainsMono-OFL.txt` |

The app no longer opens the camera, requests camera permission, or carries MediaPipe and
its face model. Upgrades migrate the existing bedtime settings and remove obsolete private
head-pose calibration samples. OLED blackout and restore do not use root; root is reserved
for dedicated-task and boot recovery.

### Bedtime reminder

The reminder is enabled by default at `23:30`. Long-press the clock to open the CRT-style
`CLOCK SETTINGS` panel, adjust the time in 15-minute steps, or disable the reminder. At the
configured time, the dial dims behind a `TIME TO REST` message and plays a short,
warm two-tone CRT chime through the media stream. This is intentionally not an
alarm: it produces no vibration or Android alarm UI.

The reminder overrides ambient-light blackout and stays visible until `DONE` is tapped. `+15 MIN`
returns to the clock temporarily and shows the reminder again after fifteen minutes.
The chime plays once when the reminder appears and once more after five minutes if
`DONE` has still not been tapped; it does not repeat indefinitely. The long-press
settings panel can disable sound independently of the bedtime reminder.
The active, acknowledged, and delayed states are stored in private app preferences, so
an active reminder survives activity recreation, service restart, and device reboot.

### Current scope

- Implemented: analog and calligraphic clock faces with a persisted style switcher, ambient-light OLED blackout, CRT-chime bedtime reminder, local Yeelight and Wake-on-LAN controls, foreground persistence, and Magisk boot recovery.
- Not implemented: alarms, alarm editing, alarm sounds, battery bypass charging, or battery-health automation.
- Planned: alarm support may be added later, but no alarm UI or scheduling behavior should be inferred from the current build.

### Build

Requirements:

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- An arm64 Android 10+ phone; an ambient-light sensor is required for automatic blackout
- Root access for MIUI boot and task-removal recovery

Build the debug APK:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The output is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On MIUI, install restrictions may require a root install:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/standby-clock.apk
adb shell su -c 'pm install -r /data/local/tmp/standby-clock.apk'
```

After every install or update on MIUI, restore its proprietary auto-start AppOp and
exclude the clock from Doze. MIUI resets AppOp `10008` during package replacement:

```bash
adb shell su -c 'cmd appops set com.henry.standbyclock 10008 allow'
adb shell su -c 'dumpsys deviceidle whitelist +com.henry.standbyclock'
```

On the tested MIUI 12 build, that AppOp is also reset during reboot before Android
can deliver `BOOT_COMPLETED`, and MIUI may later overwrite it again. Install the
included Magisk `service.d` helper once; it reapplies the policy and directly
restores only this app's service and task after each boot. Because Magisk itself
runs the helper as root, this path does not call `su` or show a root-grant toast:

```bash
adb push scripts/standby-clock-service.sh /data/local/tmp/standby-clock-service.sh
adb shell su -c 'mkdir -p /data/adb/service.d'
adb shell su -c 'cp /data/local/tmp/standby-clock-service.sh /data/adb/service.d/standby-clock.sh'
adb shell su -c 'chmod 0755 /data/adb/service.d/standby-clock.sh'
```

To suppress only this app's Magisk root-grant notification, first read its current
Android UID and then update that one Magisk policy row. Do not disable Magisk
notifications globally:

```bash
APP_UID=$(adb shell cmd package list packages -U com.henry.standbyclock | tr -d '\r' | sed 's/.*uid://')
adb shell "su -c 'magisk --sqlite \"UPDATE policies SET notification=0 WHERE uid=${APP_UID};\"'"
```

This changes only the Magisk notification policy for the clock app's UID. Root
commands launched manually through `adb shell su`, including diagnostic input
commands, can still produce a separate `Shell` root toast.

For architecture, tunable ambient-light thresholds, persistence behavior, verification,
and troubleshooting, see [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md).
