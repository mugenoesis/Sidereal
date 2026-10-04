<p align="center">
  <img src="docs/icon-512.png" alt="Sidereal" width="160">
</p>

<h1 align="center">Sidereal</h1>

<p align="center">
  <b>Gimbal control, face tracking and manual camera control for the DJI Osmo Pro.</b><br>
  An Android companion app for the Osmo Pro (Zenmuse X5), built on the DJI Mobile SDK v4 and connected over WiFi.
</p>

<p align="center">
  <a href="https://ko-fi.com/mugenoesis"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi"></a>
</p>

## Features

**Gimbal**
- **Virtual joystick**: touch anywhere on the preview and drag to aim. Double-tap to recenter.
- **A→B timed moves**: capture two positions, set a duration, and the gimbal
  makes a smooth eased move between them. You can pause and resume it.
- **Face tracking**: tap a detected face to lock onto it (on-device ML Kit).
  Choose a *Locked* or a smoother *Trail* follow style, and nudge where the
  subject sits in the frame.

**Camera**
- Exposure modes P / A / S / M with ISO, shutter, aperture and EV steppers
- Focus modes, tap-to-focus, and a software continuous-autofocus mode
  (contrast-detect hill climbing) for lenses that don't support AFC
- White balance, metering modes, and tap-to-spot-meter
- Live histogram
- Sharpness, contrast, saturation and anti-flicker
- Photo and video format, resolution and aspect ratio
- Settings the camera can't change mid-recording are greyed out while
  recording

**Media and audio**
- Browse, preview and download photos and videos from the camera's SD card
- Record audio on the phone (built-in or Bluetooth mic) alongside the video,
  since the X5 rig records no audio of its own

## Requirements

- A DJI Osmo Pro with a Zenmuse X5 (other MSDK v4 Osmo models may work but haven't been tested)
- An Android phone running Android 6.0 (API 23) or newer. The app needs real
  hardware, so it won't work in an emulator.
- A DJI developer App Key (free)

## Building

1. Register an app at [developer.dji.com](https://developer.dji.com/) and get
   an App Key for the package name `io.github.mugenoesis.sidereal`. If you
   fork the app under a different `applicationId`, register that name instead.
2. Add the key to `gradle.properties` in the project root. That file is
   gitignored, so the key never gets committed:
   ```properties
   DJI_APP_KEY=your_key_here
   ```
3. Build with JDK 17:
   ```sh
   ./gradlew assembleDebug
   ```
   Or just open the project in Android Studio and set *Settings → Build Tools →
   Gradle → Gradle JDK* to a JDK 17.

Run the unit tests with `./gradlew testDebugUnitTest`. The instrumented
tests in `app/src/androidTest` are hardware probes that need a connected Osmo.

## Using it

1. Power on the Osmo and connect your phone to its WiFi network (`OSMO_…`).
2. Launch Sidereal. Once the SDK registers and the camera connects, the
   live view appears.
3. Choose a gimbal mode (Manual, A→B, or Face Track) from the control bar.

## Known limitations

- **Stopping a video recording from the app is unreliable.** On the X5 the
  camera usually ignores the SDK's stop command, so press the physical record
  button on the Osmo to stop. The shutter button turns orange while the app
  is waiting for the camera to actually stop.
- Digital zoom and auto-zoom are implemented but untested, because the X5
  doesn't support digital zoom.
- Some value ranges (sharpness/contrast/saturation, video resolution and
  frame-rate combinations) are best guesses, because the SDK provides no way
  to query them.
- The face-tracking PID gains haven't been tuned on real hardware yet.

## Project layout

```
app/src/main/java/io/github/mugenoesis/sidereal/
├── dji/        SDK registration, connection state, camera gateway
├── gimbal/     joystick, A→B moves, mode switching, PID
├── tracking/   face detection, tracking and overlay
├── camera/     exposure, focus, WB, metering, histogram, formats, media
├── zoom/       digital zoom and size-locked auto-zoom
├── audio/      phone-side audio recording and source selection
└── media/      on-camera media library
```

## Support the project

Sidereal is free and I build it in my spare time. The APK is free to download
from this repository's releases. If you'd like to support development, you
can buy it on Google Play instead, or
[buy me a coffee on Ko-fi](https://ko-fi.com/mugenoesis). Bug reports and
ideas are welcome as GitHub issues.

The GitHub and Google Play builds are signed differently, so to switch
between them you have to uninstall one before installing the other.

## License

Sidereal is free software, licensed under the
[GNU General Public License v3.0](LICENSE). Copyright (C) 2026 mugenoesis.

**Additional permission under GNU GPL version 3 section 7:** if you modify
this program, or any covered work, by linking or combining it with the DJI
Mobile SDK (or a modified version of that library), containing parts covered
by the terms of the DJI SDK license, the licensors of this program grant you
additional permission to convey the resulting work.

## Disclaimer

This is an unofficial, independent project. It isn't affiliated with or
endorsed by DJI. "DJI" and "Osmo" are trademarks of their respective owners.
