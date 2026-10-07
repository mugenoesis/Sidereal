# Changelog

## Unreleased

### Fixed
- First start no longer pops "Single/Exposure mode ... rejected (No camera connected)" messages while the camera is
  still connecting; the modes are re-applied quietly once the camera is ready.
- The WiFi status no longer says "currently: no WiFi network" when the network name merely can't be read.
- First-time DJI registration without internet shows a plain explanation and retries by itself when the internet is back.

## 0.1.0 - first release

The first public build of Sidereal: an Android companion app for the DJI Osmo Pro (Zenmuse X5) over WiFi, with a
Wear OS watch remote.

### What it does
- Gimbal: virtual joystick, A→B timed moves, face tracking.
- Camera: exposure modes P/A/S/M, ISO, shutter, aperture and EV; focus modes, tap-to-focus and a software
  continuous autofocus; white balance, metering, live histogram; photo and video formats.
- Shooting: intervalometer, dithering, timelapse (with a day-to-night exposure ramp), panorama, dark/bias/flat frames,
  drive modes, self-timer, grid, star focus assistant, red night display; long sequences keep running with the
  screen off.
- Game controller: remappable buttons and adjustable sticks.
- Wear OS watch remote: live view, shutter, aim by dragging, status, and an Open-on-phone button. Checked on a OnePlus
  Watch 4 (Wear OS 6) and a Fossil Carlyle HR (Wear OS 2).
- Camera sounds played on the phone, phone audio recording with an audio-sync tool, and an on-camera media browser.

### First start
- The DJI SDK registers the app with DJI the first time it runs, which needs the internet once (open it on your normal
  WiFi or mobile data before joining the Osmo). If it is opened on the Osmo's WiFi first, it explains this and
  registers by itself when you get online.

### Known limits
- Stopping a video recording from the app usually doesn't work on the X5: press the camera's own record button.
- Digital zoom isn't supported by the X5.
- Face tracking, the day-to-night ramp, very long sequences and several other features are still beta - see the
  README's feature status.
- Only the DJI MFT 15mm f/1.7 lens has been tested.

### Not yet
- The Google Play version is coming, but isn't available yet.
