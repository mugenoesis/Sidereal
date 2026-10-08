# Changelog

## 0.2.0 - photos on the phone: download, stitch, timelapse video

### New
- After a sequence finishes, its photos can be brought onto the phone automatically into their own folder
  (`Pictures/Sidereal/<Mode>_<date>_<time>/`). Every file is named with its place in the series ahead of the camera's
  own name - `Panorama_2026-10-08_0131_r2c3_DJI_0398.JPG`, `Timelapse_..._f0042_...`, `Darks_..._dark007_...` - and a
  RAW and its JPEG get the same label. New *Save photos* option per mode; on by default except for timelapse.
- Panorama stitching on the phone (*Stitch*, on by default): placed by the gimbal's angles, refined by matching details
  between frames, blended, cropped, and saved next to the frames.
- Timelapse video (*Make video*, off by default): frames are encoded into an H.264 MP4 as they download, saved to
  `Movies/Sidereal/<series>/`.
- The plan summary says what will happen afterwards and roughly how long the download adds.
- Progress for the download, stitching and encoding shows in the tray, the banner and the notification.

## Unreleased

### Fixed
- The on-camera media browser lists the newest files first instead of oldest first.

## 0.1.1 - first-start fixes

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
