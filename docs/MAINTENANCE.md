# Maintenance Guide

This document describes the current native Android implementation. It is intended
to prevent future UI, ambient-light automation, and MIUI persistence changes from drifting
away from the behavior already validated on the dedicated phone.

## Supported target

- Tested device: rooted Xiaomi Mi 9 Pro 5G
- Tested operating system: Android 10 / MIUI 12
- Orientation: permanently landscape
- Android package: `com.henry.standbyclock`
- Minimum SDK: 29 (Android 10)
- Target / compile SDK: 35
- Packaged ABI: `arm64-v8a`
- Java toolchain: JDK 17

Other Android 10+ arm64 devices may work, but their light-sensor behavior, foreground
service rules, vendor auto-start controls, and root manager behavior have not been
validated by this project.

## Runtime architecture

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Owns the fullscreen landscape window, immersive mode, style persistence and hourly rotation, brightness override, touch interaction, and service connection. |
| `ClockView` | Draws both clock faces plus bedtime and settings overlays with Android Canvas. It also owns swipe recognition, pixel shifting, one-second invalidation, and pure-black rendering. |
| `TrafficStatusClient` | Reads the private VPS bridge, bounds the response size, and parses independent HostVDS and Sanmao provider states. |
| `TrafficStatusFormatting` | Converts provider values, stale states, reset dates, and expiry dates into fixed English-only overlay strings. |
| `standby-clock.properties` | Untracked build-time values for LAN devices, Wake-on-LAN, provider labels, and the traffic bridge endpoint. |
| `ClockStyleSwitching` | Contains the pure vertical-gesture and one-hour deadline rules covered by local unit tests. |
| `StandbyService` | Monitors the ambient-light sensor, owns visible/blackout state, runs the bedtime schedule, and restores the clock task after removal. |
| `AmbientLightPolicy` | Contains the pure hysteresis and temporal-confirmation rules covered by local unit tests. |
| `TouchWakePolicy` | Contains the pure tap-to-wake window arithmetic covered by local unit tests. |
| `BedtimeSchedule` | Contains the deterministic daily-trigger and 15-minute adjustment calculations covered by local unit tests. |
| `PersistenceReceiver` | Handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, starts the foreground service, and restores the clock task. |
| `RootShell` | Brings the clock task forward and reads the rooted phone's LAN neighbor table. |
| `scripts/standby-clock-service.sh` | Magisk `service.d` boot helper for MIUI AppOps, Doze exclusion, service start, and task restore. |

The app declares network access for its trusted-LAN device controls but declares no
camera or storage permission. It captures no images and bundles no machine-learning
model. On the first upgraded start, existing bedtime preferences are migrated from the
legacy service and obsolete private `files/gaze_calibration` data is deleted.

## Clock rendering contract

The display is intentionally English-only. Do not add localized day or month
labels unless the visual direction is explicitly changed.

- Background: pure black (`#000000`)
- Main phosphor color: P3-style amber (`#FFB000`)
- Warm glow color: orange (`#FF6E00`)
- Date/time accent: orange (`#FF9100`)
- Typeface: VT323, bundled at `app/src/main/res/font/vt323_regular.ttf`
- Typeface license: `third_party_licenses/VT323-OFL.txt`
- Dial: 60 marks projected onto a rounded-rectangle/superellipse perimeter; cardinal marks are longest and there are no numerals
- Hands: sharp filled paths with axial color gradients, phosphor bloom, and a blended center trace
- Information line: a single right-side string such as `WED 5   19:51`; it includes 24-hour time and no month or status dot
- Surface treatment: subtle black scanlines are drawn over the completed clock frame
- Updates: once per second
- OLED mitigation: the whole dial shifts a few pixels every five minutes

The normal clock face accepts a vertically dominant swipe of at least 10% of the
view height (and at least four platform touch-slop units). Up selects the next style;
down selects the previous style. Blackout, reminders, and settings do
not accept style swipes. Recognized swipes cancel the View's default touch sequence so
they cannot also fire a click or long-press.

Style changes render the outgoing and incoming faces in separate alpha layers for
380 ms using the same cubic-bezier easing as the calligraphic digit transitions. The
outgoing face travels 12% of the view height in the requested direction while fading;
the incoming face starts 12% away on the opposite side. A second style change is
rejected until the active transition completes. CRT scanlines belong to the phosphor
face layer, while reminder and settings scanlines remain stationary overlays.

Hourly style rotation is enabled by default. Its next wall-clock deadline is stored
with the selected style in `clock_preferences`. Any manual style change resets the
deadline to one hour later. `MainActivity` removes the in-memory callback while stopped;
on restart it preserves a valid future deadline, advances once when overdue, and then
starts a fresh hour. The settings panel can disable automatic rotation and clear the
pending deadline.

Alarm support is deliberately outside the current scope. The bedtime reminder is a
short-chime in-app prompt and must not acquire repeating alarm sounds, vibration, or
system alarm UI without a separate product decision.

## Traffic status contract

- A horizontally dominant right swipe opens `DEVICE CONTROL`; a left swipe opens
  `TRAFFIC STATUS`. A horizontal swipe or ordinary tap closes either overlay.
- The Android app reads `traffic.statusUrl` from the ignored
  `standby-clock.properties` file. Cleartext traffic is supported for a read-only,
  low-sensitivity personal endpoint.
- The phone never stores the HostVDS Cookie or the Sanmao subscription URL. Those
  secrets live only under the deployed bridge's `config/` directory.
- The VPS bridge is maintained in `vps/traffic-status`, runs as unprivileged UID 1000
  in Docker, and exposes only `GET /traffic`, `HEAD`, and `GET /healthz`.
- HostVDS values use the provider billing formula and decimal GB. Sanmao values use
  the standard `Subscription-Userinfo` response header and binary GiB.
- Each provider fails independently. A failed refresh preserves its last successful
  values and marks them stale; an expired HostVDS session is displayed as
  `COOKIE EXPIRED`, never as zero remaining traffic.
- The bridge cache lifetime is ten minutes. Opening the traffic overlay requests the
  bridge immediately, while the bridge prevents excessive upstream polling.

Copy `standby-clock.properties.example` to `standby-clock.properties` before a
configured Android build. Missing values intentionally produce `NOT CONFIGURED`
instead of a network request. The optional `STANDBY_CLOCK_CONFIG` environment
variable can select a different properties file for CI or alternate installations.

Yeelight control stores both `yeelight.host` and `yeelight.mac`. The host remains a
backward-compatible fallback, while the rooted dedicated phone resolves the stable MAC
from `ip neigh`, caches the current address, and refreshes its `/24` Wi-Fi neighbors only
after an address failure. A read-only protocol query verifies a recovered address before
the app sends `toggle`, which is never automatically retried because it has side effects.

On the VPS, copy `.env.example` to `.env` and the two `config/*.example` files to
their names without `.example`. Update the HostVDS session without placing it in
shell history:

```bash
ssh -t henry-vps '/home/henry/traffic-status/set-hostvds-cookie'
```

## Bedtime reminder contract

- Default schedule: enabled at `23:30`, adjustable in 15-minute steps from the long-press settings panel.
- Trigger check: every 15 seconds in `StandbyService` while the foreground service is alive.
- Active behavior: show `TIME TO REST`, use a low fixed brightness, suppress ambient-light blackout, play the bundled CRT chime, and persist across process/device restart.
- `DONE`: acknowledge the reminder's calendar date and do not trigger it again that day.
- `+15 MIN`: hide it for exactly fifteen minutes, then reactivate and remain visible again.
- Sound: enabled by default and independently switchable from the settings panel. It uses Android's media stream and therefore follows media volume and mute; Do Not Disturb handling follows the phone's media policy.
- Sound cadence: play once on activation and once after five minutes if the reminder remains active, then stop. Reactivation after `+15 MIN` starts a new two-play cadence.
- Sound persistence: play count and next-play timestamp are stored so a service restart cannot restart the cadence from the first sound.
- Time adjustment cancels a pending delay so an old snooze cannot unexpectedly fire after the schedule changes.
- OLED mitigation: the active reminder uses the same minute-based pixel-shift offsets as the dial.
- Persistence: enabled state, time, active reminder date, acknowledged date, and snooze deadline live in private `SharedPreferences`.

The reminder contains no `AlarmManager`, notification alarm, or vibration path. Its
only audio is the bundled `res/raw/bedtime_crt_chime.wav` resource, played with
`AudioAttributes.USAGE_MEDIA`.

## Ambient-light policy and tunable parameters

`StandbyService` first requests the default wake-up `TYPE_LIGHT` sensor and falls back
to the normal light sensor if necessary. A device without either sensor remains visible
instead of risking an unrecoverable blackout. Sensor events are inexpensive and require
no runtime permission.

The primary tuning constants are in `AmbientLightPolicy.java`:

| Constant | Current value | Meaning |
| --- | ---: | --- |
| `DARK_THRESHOLD_LUX` | 3 lux | At or below this value, darkness confirmation starts. |
| `BRIGHT_THRESHOLD_LUX` | 15 lux | At or above this value, brightness confirmation starts. |
| `DARK_CONFIRMATION_MS` | 20000 ms | Sustained darkness required before blackout. |
| `BRIGHT_CONFIRMATION_MS` | 3000 ms | Sustained brightness required before restoring the clock. |

Values between the two thresholds preserve the current state and cancel an in-progress
transition. This hysteresis prevents flicker. Confirmation uses a scheduled callback as
well as sensor events because Android light sensors may report only when their value
changes. Once brightness is confirmed, there is no visibility timeout: the clock stays
on indefinitely until darkness is separately confirmed.

## Blackout behavior

Blackout is not Android sleep, AOD, or lock-screen mode. It is an application state:

1. `StandbyService` broadcasts blackout after twenty seconds of confirmed darkness.
2. `ClockView` draws only pure black and stops its once-per-second ticker.
3. `MainActivity` changes the window brightness override to `0.0`.
4. The foreground monitoring service, light sensor, and partial wake lock remain active.
5. Three seconds of confirmed brightness restores normal rendering and brightness, which then remain visible without a timeout.

An active bedtime reminder is the intentional exception: it forces the application
display visible at low brightness until `DONE` or `+15 MIN` is selected. Opening the
settings panel also temporarily holds the display visible; closing or leaving the
activity releases that hold.

Tap-to-wake is the second exception. Without it a blacked-out screen ignores every
touch, so a dark room can only be escaped through the physical light switch. Any touch
opens a twenty-second window (`TouchWakePolicy.WAKE_DURATION_MS`) during which the
display is held visible at `MainActivity.TOUCH_WAKE_BRIGHTNESS` (`0.15`), and every
further touch refills it. The rule is simply that blackout never happens within twenty
seconds of the last touch.

`MainActivity` owns this window rather than `StandbyService`, because it is a transient
UI state that can only exist while the activity is in the foreground, and because a
broadcast round-trip would make the response lag the finger. The window is applied in
`Activity.dispatchTouchEvent` before the event reaches `ClockView`, so one uninterrupted
right swipe out of blackout still opens the device controls. `StandbyService` keeps
reporting blackout throughout; the window only suppresses it locally, and it is dropped
as soon as confirmed brightness restores the display or the activity stops.

This avoids a portrait lock-screen/AOD transition before the landscape clock appears.
It also means the device is still logically awake and will continue consuming power.
Battery bypass charging and battery-health policy are not implemented.

## Root and persistence boundaries

The clock UI, ambient-light automation, and OLED blackout do not require root. Root is
used only to make the device behave like a dedicated appliance:

- Restore the task after it is removed.
- Restore the service and activity during boot.
- Reapply MIUI auto-start/background AppOps and Doze whitelist state.
- Optionally suppress only this app UID's Magisk grant notification.

The Magisk helper runs directly as root and therefore does not invoke `su` during
normal boot. The app's `RootShell` path does invoke `su`; make sure the Magisk policy
for the app UID remains granted and has `notification=0` if task-recovery toasts
must stay hidden. A toast naming `Shell` is normally caused by an ADB diagnostic
command, not by the clock app.

After reinstalling the app, confirm that the package UID did not change before
assuming the existing Magisk policy still applies.

## Build, install, and verify

Run the complete local check:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

Normal install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

MIUI root fallback when normal ADB installation returns
`INSTALL_FAILED_USER_RESTRICTED`:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/standby-clock.apk
adb shell su -c 'pm install -r /data/local/tmp/standby-clock.apk'
```

Useful read-only checks:

```bash
adb shell dumpsys activity activities | grep -m 1 mResumedActivity
adb shell dumpsys activity services com.henry.standbyclock
adb shell dumpsys deviceidle whitelist | grep com.henry.standbyclock
adb shell su -c 'cmd appops get com.henry.standbyclock'
adb logcat -d -s StandbyService:I StandbyPersistence:I StandbyBoot:I '*:S'
```

Expected steady state:

- `MainActivity` is resumed when the clock is visible.
- `StandbyService` is a foreground special-use service with notification ID `41`.
- Logcat reports the selected ambient sensor and periodically samples `ambient_lux`.
- Covering the light sensor with sustained readings at or below `3 lux` produces `display_mode=BLACKOUT` after about twenty seconds.
- Exposing the sensor to readings at or above `15 lux` produces `display_mode=VISIBLE` after about three seconds.
- The display remains visible indefinitely while the environment stays bright.
- The package requests no camera permission and Android shows no camera privacy indicator.
- The blackout screenshot contains only black pixels.
- A tap during blackout restores the clock at low brightness, logs `touch_wake=ON`, and logs `touch_wake=OFF` about twenty seconds after the last touch.
- A right swipe that starts during blackout opens the device controls in one gesture.

Blackout-dependent behavior can be exercised without waiting for a dark room by
replaying the display-mode broadcast and injecting input:

```bash
adb shell su -c 'am broadcast -a com.henry.standbyclock.action.DISPLAY_MODE -p com.henry.standbyclock --ez blackout true'
adb shell input tap 1170 540
adb exec-out screencap -p > /tmp/clock.png
adb shell su -c 'am broadcast -a com.henry.standbyclock.action.DISPLAY_MODE -p com.henry.standbyclock --ez blackout false'
```

Root is required for the broadcast. `MainActivity` registers its receiver as
`RECEIVER_NOT_EXPORTED`, so the same command from the plain `adb shell` user is
accepted by `am` and then silently dropped, which looks like a working command that
changes nothing. Only `MainActivity` is affected: `StandbyService` keeps its real
ambient state, so the final broadcast above restores the display, and any genuine
sensor transition would have corrected it anyway. A screenshot is the reliable check,
because the light sensor only reports on change and can stay silent for minutes in a
steady room.
- At the configured bedtime, the reminder overrides darkness and survives an activity/service restart.
- `DONE` returns control to ambient-light blackout; `+15 MIN` hides the reminder and persists its delayed state.
- With sound enabled, logcat reports `bedtime_sound=PLAY count=1`, and reports `count=2` only if the reminder remains active for five minutes.

## Troubleshooting

### Ambient light does not switch correctly

- Inspect `StandbyService` logs for the selected sensor and recent `ambient_lux` values.
- Check that the phone case or stand does not permanently cover the sensor near the top bezel.
- Measure the installed position with the room dark, with normal daylight, and with the ceiling light on before changing thresholds.
- Keep separate dark and bright thresholds; collapsing them to one cutoff can cause display flicker.
- If no `TYPE_LIGHT` sensor is available, the intentional safe fallback is continuous visibility.

### App does not return after reboot

- Confirm `/data/adb/service.d/standby-clock.sh` exists and is executable (`0755`).
- Check `StandbyBoot` logcat output.
- Confirm MIUI AppOp `10008` and background AppOps are allowed.
- Confirm the package is still on the Doze whitelist.
- Confirm Magisk is running `service.d` scripts.

### A superuser toast appears

- If it names the clock app, verify the Magisk policy for the current package UID.
- If it names `Shell`, stop using `adb shell su` commands while observing normal behavior; the toast belongs to the diagnostic shell session.
- Do not disable Magisk notifications globally.

## Change checklist

Before handing off a future release:

1. Run unit tests, APK assembly, and lint.
2. Install on the dedicated phone without clearing application data.
3. Confirm the activity is resumed and the ambient-light foreground service is running.
4. Visually inspect both clock faces at several times so transitions and long cardinal marks are covered.
5. Verify up/down style swipes, click/long-press isolation, the one-hour countdown reset, and the `AUTO SWITCH` toggle.
6. Verify twenty-second dark blackout, three-second bright restore, and indefinite visibility while bright.
7. Verify tap-to-wake during blackout: low-brightness restore, the twenty-second timeout, and a single right swipe reaching the device controls.
8. Verify bedtime activation, persistent visibility, both sound plays, `DONE`, `+15 MIN`, and the long-press settings controls.
9. Reboot once after persistence changes and inspect `StandbyBoot` logs.
10. Update this file and the README if constants, installation steps, UI text, or scope change.
