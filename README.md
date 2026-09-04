# KS03 Old — barebones Android controller

Minimal Android app for KS03-prefixed BLE LED controllers (old protocol,
device name like `KS03-AAABBB`). No manual address entry — it scans and
lists nearby devices, tap one to connect.

Direct port of `cheshire/hal/compilers/ks03_old/platform_commands.py`.
Same byte frames, same quirks (0-100 color/brightness range, inverted
music-mode speed, unverified music-mode trailer).

## What it does
- Scans for BLE devices advertising a name starting with `KS03-`
- Connects, resolves GATT service `fff0` / characteristic `fff3`
- Lets you: power on/off, set solid RGB (0-100 sliders), set brightness,
  set speed, trigger any of the 6 scenes (Jump7/Jump3/Fade7/Fade3/Flash/Auto)

Music modes are intentionally not exposed in the UI — upstream flags that
frame as untested against real hardware.

## Building locally
```
./gradlew assembleDebug
```
APK lands in `app/build/outputs/apk/debug/`.

## CI
`.github/workflows/release.yml` runs on every push to `master`:
1. Computes the next version number as `(highest existing vN tag) + 1`,
   starting at `1` if no tags exist yet.
2. Builds a release APK with that version baked in.
3. Publishes a GitHub Release tagged `vN` with the APK attached.

No manual trigger, no manual version bumping — just push to master.

Note: the release APK is unsigned. Android will let you sideload it with
"install from unknown sources" enabled, but if you want it signed (e.g.
for Play Store or to avoid the unsigned-APK warning) you'll need to add
a signing config and store the keystore as a repo secret — not set up
here to keep this barebones.
