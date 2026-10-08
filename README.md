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
  (contrast detection on the live view) for lenses that don't support AFC: a
  quick climb to the sharpest focus in clean light (low ISO), a scanning
  search in noisy low light
- White balance, metering modes, and tap-to-spot-meter
- Live histogram, with a red marker when the highlights are clipping
- Camera sounds played on the phone (shutter click, self-timer beeps, record
  start/stop, focus-lock beep), each switchable in the More tray. The DJI SDK
  has no setting for the camera's own sounds, so the app plays them itself.
- Sharpness, contrast, saturation and anti-flicker
- Photo and video format, resolution and aspect ratio
- Settings the camera can't change mid-recording are greyed out while
  recording

**Shooting**
- Intervalometer with a settling delay, optional dithering between frames,
  motion timelapse (an A→B gimbal move spread across the run), matrix
  panorama, and dark / bias / flat calibration frames
- Day-to-night ("holy grail") timelapse ramp: meters the live histogram
  before each frame and follows the light with shutter first, then ISO, in
  smooth third-stop steps. *Keep darkness* sets how much of the fading light
  stays in the frames, and *Max ISO* caps the noise. It opens the lens to its
  widest aperture when it starts (and shortens the shutter to match) so the
  night end of the ramp has the full range, and it works however the app was
  opened
- After a run, the photos can come off the camera by themselves into a
  folder that says what they are: `Pictures/Sidereal/Panorama_2026-10-08_0131/`
  holds `Panorama_2026-10-08_0131_r2c3_DJI_0398.JPG` and so on, with the
  position in the series (`r2c3` = row 2, column 3; `f0042` = frame 42; `dark007`)
  ahead of the camera's own file name, so a RAW and its JPEG stay paired.
  Each mode has its own on/off option (*Save photos*; on by default except for
  timelapse, which is a lot of data)
- **Panorama stitching:** the frames are joined into one picture on the phone,
  placed by where the gimbal pointed and then refined from the pictures
  themselves (it finds matching details in neighbouring frames and corrects
  small aiming errors and the lens' true field of view). The result is cropped
  to a clean rectangle and saved beside the frames. On by default
- **Timelapse video:** the frames are downloaded and encoded to an MP4 as they
  arrive (up to 2304×1728, H.264, at the chosen fps), so a long timelapse
  doesn't need space for every frame at once. Off by default - bringing
  hundreds of photos across the camera's WiFi takes a while (about 3.5 s each)
- Long sequences keep running with the screen off or the app in the
  background (a foreground service with a progress notification and a Stop
  button), and they wait out a dropped camera link instead of giving up
- Drive modes (single, burst, AEB bracketing), self-timer, exposure lock
  and a composition grid
- Star focus assistant: magnified star with a live FWHM readout
- Red night display that keeps your night vision
- Battery, card space and recording time at a glance
- Colour profiles (D-Log, D-Cinelike, B&W and more), PAL/NTSC and the
  camera's own list of video resolutions and frame rates

**Wear OS**
- A watch remote (separate `wear` module): live view, shutter or record,
  photo/video switch, drag to aim the gimbal, battery and card status, a
  button to make the phone join the Osmo's WiFi, and an *Open on phone*
  button for when the phone app isn't running. The phone app stays the only
  thing that talks to the camera.

**Game controller**
- Bluetooth or USB gamepad: left stick aims the gimbal, right stick zooms,
  R2 is the shutter, R1 switches photo/video, L1/L2 pull focus, hold A to
  show the focus crosshair in the middle (aim the gimbal with it up) and
  let go to focus there, d-pad left/right step P/A/S/M, d-pad up/down
  change exposure compensation, X locks exposure, Y shows the grid.
  Every button can be remapped from a pop-out list (More > Game controller),
  the same action can sit on several buttons, and the stick speeds, dead
  zone and response are adjustable.

**Media and audio**
- Browse, preview and download photos and videos from the camera's SD card
- Record audio on the phone (built-in or Bluetooth mic) alongside the video,
  since the X5 rig records no audio of its own
- Audio sync: line the phone audio up with the video by ear against a live
  preview, then export one merged MP4 (no re-encoding)

## Download

Grab the latest APK from the [Releases page](../../releases/latest) and install
it on your phone (Android will ask you to allow installs from your browser or
file manager the first time). The APK already includes a DJI App Key registered
to this app, so you don't need your own to use it.

**A Google Play version is coming, but it isn't available yet.** For now this
GitHub release is the only way to get Sidereal.

**First start needs the internet, once.** DJI's SDK registers the app with DJI
the first time it runs, so open Sidereal while you're on your normal WiFi (or
mobile data) before you connect to the Osmo. If you open it already on the
Osmo's WiFi, it says so and finishes registering by itself as soon as you get
online. After that it never needs the internet again.

The Wear OS watch app is a separate APK on the same release. Watches have no
store entry for it yet, so it is installed from a computer with
`adb install sidereal-wear-<version>.apk` (turn on ADB debugging on the watch
first). It works with Wear OS 2 and newer.

## Requirements

- A DJI Osmo Pro with a Zenmuse X5. Only the DJI MFT 15mm f/1.7 lens has
  been tested so far, and other MSDK v4 Osmo models may work but haven't
  been tried. Other Micro Four Thirds lenses should work (see *Lenses*
  below).
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

### Release builds

To make a signed release, copy `keystore.properties.example` to
`keystore.properties` (gitignored), point it at your own keystore, then run:

```sh
./gradlew assembleRelease   # signed APK, for GitHub releases
./gradlew bundleRelease     # signed app bundle (.aab), for Google Play
```

Without a `keystore.properties`, release builds are produced unsigned.

Run the unit tests with `./gradlew testDebugUnitTest`. Debug builds also
include an adb-driven hardware test harness (`app/src/debug`) that runs
scenarios against a connected Osmo and logs `RESULT … PASS/FAIL` lines.

## Using it

1. Power on the Osmo and connect your phone to its WiFi network (`OSMO_…`).
2. Launch Sidereal. Once the SDK registers and the camera connects, the
   live view appears.
3. Choose a gimbal mode (Manual, A→B, or Face Track) from the control bar.

## Lenses

Sidereal was built and tested with the DJI MFT 15mm f/1.7. It does not assume that lens everywhere:

- **Focal length.** The camera reports the lens name ("DJI MFT 15mm F1.7 ASPH"); the app reads the focal length out of
  it and uses it to plan panoramas (how many frames, how much they overlap), to size dither, and as the stitcher's
  starting field of view. *Focal length* in the panorama options is *Auto* (what the camera reports, else 15 mm) or a
  value you choose. The stitcher also reads the focal length stored in the photos and corrects itself if the plan used a
  different one.
- **Zoom lenses.** A zoom reports its range but not where it is set, so the panorama options ask you to set the focal
  length; until you do, it plans for the wide end (extra overlap, never gaps).
- **Aperture.** The day-to-night ramp opens the lens to the widest aperture the camera accepts, so it works with
  whatever the lens offers; a lens without aperture control is left alone.
- **Autofocus.** The software autofocus reads the focus ring's range from the camera, but its settings were tuned on
  the 15 mm lens, so another lens may lock slower or less precisely.

| Lens | Status |
|---|---|
| DJI MFT 15mm f/1.7 | Tested; everything in this README was checked on it |
| Olympus M.Zuiko 45mm f/1.8 | Planned |
| Olympus M.Zuiko 14-42mm f/3.5-5.6 EZ (power zoom) | Planned |
| Anything else (Micro Four Thirds) | Untried; should work as above, tell me how it goes |

The [lens test plan](docs/lens-test-plan.md) lists exactly what will be checked on the two Olympus lenses, and
`tools/lens_test.sh` runs the parts that can be automated.

## Feature status

Everything below was tested on an Osmo Pro with a Zenmuse X5 and the DJI
MFT 15mm f/1.7 lens. Other lenses haven't been tried yet (see *Lenses* above).

### ✅ Working

- Connecting over WiFi, DJI SDK registration and live preview
- Virtual joystick gimbal control and double-tap recenter
- A→B timed moves
- Face detection and tap-to-lock
- Exposure modes P / A / S / M with ISO, shutter, aperture and EV
- White balance, metering modes and tap-to-spot-meter
- Focus modes and tap-to-focus
- Sharpness, contrast and saturation
- Photo format and aspect ratio
- Locking out settings the camera can't change mid-recording
- Starting video recording and taking photos from the app
- Browsing and downloading photos and videos from the SD card
- Intervalometer, dithering, timelapse, panorama and calibration frames
  (checked against the real number of files on the card)
- Bringing a run's photos onto the phone into a labelled folder, stitching a
  panorama, and encoding a timelapse video (checked on the camera with real
  runs; the stitch was checked on an indoor scene)
- Drive modes single / burst 3, 5, 7 / AEB 3, 5, self-timer, exposure lock
  and composition grid
- Star focus assistant (a manual focus sweep over a point light gives a
  clean FWHM minimum at best focus)
- Red night display (checked pixel by pixel: no green or blue is drawn)
- Video resolution and frame rate, video standard and colour profile, all
  read from the camera and verified by setting and reading back
- Sharpness, contrast and saturation range of −3 to +3
- Live histogram: the camera's 64 luma buckets (video range) are checked
  against screenshots of the preview, and the display follows the exposure
- Software autofocus: whenever the picture is clean (ISO up to 6400, which
  covers bright sun and a dim room in daylight) it climbs straight to the
  sharpest focus and locks in about 5–8 s, averaging more frames when there is
  noise; at higher ISO, or if the picture is too noisy to climb, it scans for
  the peak instead and takes about 6–14 s. It searches again after you pan to
  a new scene. Checked on the camera in bright sun, a dim room and a dark scene
  at ISO 3200–12800
- ISO and shutter readouts follow what the camera is set to
- Camera sounds: shutter click, self-timer beeps, focus-lock beep and the
  on/off options (checked on the phone)

### 🧪 Beta

- **Face tracking:** follows a locked face, but it still needs tuning to be
  smooth and accurate.
- **Day-to-night ramp:** checked on the camera in daylight by panning from a
  window to a dim room and back, which it followed in smooth steps, and from a
  lens left at f/8 (it opened to its widest, f/1.7 on the test lens, and kept the picture's brightness). It
  hasn't seen a real sunset yet.
- **Long-running sequences:** a one-minute run kept shooting with the screen
  off, and the notification, wake lock and WiFi lock were released at the end.
  Runs of several hours, a real WiFi drop and recovery mid-run, and swiping
  the app away from recents haven't been tested. On Android 13 and newer the
  app asks for notification permission the first time you start a sequence.
- **Software continuous autofocus:** seeds from the camera's own
  autofocus, finds the sharpness peak (a quick climb in clean light, a scan in
  noisy low light), locks, and then leaves the ring alone until the scene
  changes. It hasn't been tested while recording video. At ISO 12800 and above
  it can still lock off the peak, and in a dim room under flickering LED light
  some runs (2 of 10 measured) lock off the peak or fall back to the slower
  scan.
- **Phone audio recording and sync:** records from the phone's mic or a
  Bluetooth mic alongside the video, and the Audio sync screen lines it up
  and exports a merged MP4. The automatic starting offset is only as good
  as the camera's reported record-start time, so check it by ear.
- **Game controller:** checked with an 8BitDo Ultimate and with injected
  events. Other controller families report their buttons with different
  codes, so tell me if one of yours maps wrongly. Trigger pulls reported both
  as a button and as an axis are treated as one press.
- **Camera sounds on record start and stop:** built and unit-tested, but not
  tried on the camera, because the app can't stop a recording itself.
- **Wear OS watch app:** checked on two real watches: a OnePlus Watch 4
  (Wear OS 6, with a Samsung Galaxy Fold) and a Fossil Carlyle HR (Wear OS 2,
  Android 9, with an LG G8X). On both, live status, the shutter, live view, drag
  to aim and the *Open on phone* button work over the real Bluetooth link. The
  app supports Wear OS 2 and newer (Android 7.1+). The
  live view runs at about 4-7 frames a second with the picture a fraction of a
  second behind: it sends only as many frames at once as keep that lag under a
  limit, and sharpens or softens the picture to suit the link. Other
  watches haven't been tried. Because the picture is dragged to aim, the
  watch's swipe-to-dismiss gesture is switched off on that screen; leave the
  app with the watch's own button.
- **Joining the Osmo's WiFi from the app** (tap the "not on your Osmo's
  WiFi" message, Android 10+): works, but Android asks you to confirm the
  network the first time. Uses the factory password `12341234` unless you
  long-press the message and enter yours.
- **Switching video standard (PAL/NTSC):** works, but the camera takes
  about five seconds to settle afterwards and refuses queries meanwhile.
  Flipping it repeatedly in a short time once left the camera's media
  browser stuck until the Osmo was power-cycled, so don't toggle it for fun.
- **HDR and burst of 10:** the camera lists HDR but rejects it, and rejects
  a burst of 10, so only the modes that work are offered.
- **Star focus assistant measures the phone's preview**, so the number is
  in preview pixels: use it to find the smallest value, not as an absolute
  star size.
- **Face tracking after the subject leaves the frame:** the tracker doesn't
  recognise the same person when they come back, so tap them again to
  relock.

### ❌ Not working

- **Stopping a video recording from the app:** the X5 usually ignores the
  stop command. Press the physical record button on the Osmo to stop. The
  shutter button turns orange while the app waits for the camera to stop.
- **Digital zoom and auto-zoom:** they're built but untested, because the
  X5 doesn't support digital zoom.

## Project layout

Other modules: `wear/` is the Wear OS app and `wearprotocol/` is the plain-Kotlin
protocol and display logic shared by the phone and the watch.

```
app/src/main/java/io/github/mugenoesis/sidereal/
├── dji/        SDK registration, connection state, camera gateway
├── gimbal/     joystick, A→B moves, mode switching, PID
├── tracking/   face detection, tracking and overlay
├── camera/     exposure, focus, WB, metering, histogram, formats, media
├── sequence/   intervalometer, timelapse, panorama, calibration frames
├── series/     after a run: file naming, download, panorama stitching, timelapse video
├── focus/      star finder, FWHM metrics and the focus assistant
├── display/    red night mode
├── zoom/       digital zoom and size-locked auto-zoom
├── audio/      phone-side audio recording and source selection
├── sync/       audio/video offset, sidecar files, MP4 merge and the sync screen
├── input/      game controller mapping
├── wear/       watch bridge on the phone (commands, status, live-view stream)
└── media/      on-camera media library
```

Outside the app code: `docs/` holds the privacy policy and `store/` the Google Play listing text, a console guide and
the script that draws the store graphics from the app icon.

## Privacy and permissions

Sidereal has no accounts, analytics or ads, and nothing you shoot leaves your devices because of it. The
[privacy policy](docs/privacy-policy.md) has the detail. The permissions it asks for, and why:

- **Location** - only so Android lets it read the name of the WiFi network, to tell whether you are on the Osmo's WiFi.
- **Nearby devices** - to show the name of a Bluetooth microphone for audio recording.
- **Microphone** - only when you record audio on the phone.
- **Notifications** - progress of a long sequence, and when it has finished.

It deliberately asks for no camera, gallery-read, overlay or location-history access. (On Android 9 and older it also
needs storage access, because those versions have no other way to save to the gallery.) The DJI SDK's own manifest
requests a few more - drawing over other apps, ending other apps' processes, listing running tasks - which this app
removes, since none is needed to talk to the Osmo.

## Support the project

Sidereal is free and I build it in my spare time. The APK is free to download
from this repository's releases. A Google Play version is coming soon (it isn't
there yet); when it arrives you'll be able to buy it there to support
development, or you can
[buy me a coffee on Ko-fi](https://ko-fi.com/mugenoesis) any time. Bug reports
and ideas are welcome as GitHub issues.

Whether the GitHub and Google Play builds can update each other depends on how
the Play signing key is set up; until the Play version is out, assume that
switching between them means uninstalling one first.

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
