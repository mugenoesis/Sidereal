# Panasonic Lumix G Vario 12-32mm f/3.5-5.6 on the Zenmuse X5 - findings

Session of 2026-10-08. App 0.2.1 plus lens-awareness work. Raw dumps and step reports are in this folder.

## What the camera tells the app about this lens

| Thing | Value |
|---|---|
| `getLensInformation` / `LENS_INFORMATION` | `Unknown` (the camera only names DJI's own lenses) |
| Lens name in a photo's EXIF `LensModel` | `LUMIX G VARIO 12-32/F3.5-5.6` |
| EXIF `FocalLength` | **12.0 at every zoom position, and with the lens stowed** (so EXIF cannot give the focal length) |
| EXIF `FNumber` | correct (3.5, 4.0, 5.0) |
| Optical-zoom calls | "unsupported" (the ring is mechanical) |
| `FOCUS_RING_VALUE_UPPER_BOUND` | follows the zoom: 1570 (12), 1680 (14), 2214 (18), 2633 (25), 3830-3837 (32) |
| `APERTURE` (program mode) | follows the zoom: f/3.5, f/3.5, f/4, f/5, then **f/4 at 32** (wrong, see below) |
| `LENS_IS_INSTALLED`, `HAS_ERROR` | true / false even when the lens is stowed |

## Stowed lens

The camera raises no error and takes photos. The only sign is `FOCUS_RING_VALUE`: **-26270** stowed, a normal value (within 0..upper bound) once extended. The app now watches for this and shows "Lens not extended - rotate the zoom ring".

## The hang at 32 mm

- Program mode at 32 mm: the first shot starts and never finishes (`IS_SHOOTING_PHOTO` and `IS_STORING_PHOTO` stay true, `IS_SHOOTING_PHOTO_ENABLED` false). Every later shot is refused with "Camera is busy or the command is not supported in the Camera's current state". The media mode cannot be entered either. Only a camera power cycle clears it.
- Manual mode at 32 mm: works for every shutter from 1/60 up to 2 s, with manual or auto focus.
- Manual mode at **f/5.6**: works. At **f/4**: `setAperture(F_4)` is accepted ("success") but the lens stays at f/5.6, and the next shot hangs exactly like program mode did.

Conclusion: the camera asks the lens for an aperture it cannot make at that zoom (the camera's program mode picks f/4 at 32 mm; the lens' real widest at 32 mm is f/5.6), the request is not applied, and the shot never completes. Variable-aperture zooms are at risk at their long end.

## What the app does about it

- The ramp opens the aperture only as wide as the lens' widest aperture at its longest zoom (f/5.6 here), and leaves the aperture alone for a lens it cannot identify.
- The aperture stepper hides apertures an identified lens cannot make.
- Not covered: the Panasonic is still "Unknown" to the app, and program mode at 32 mm still picks f/4 on its own.

## Checked on the camera (2026-10-08, later)

- Tap the "Lens unknown" line: the newest photo's EXIF names the lens ("LUMIX G VARIO 12-32/F3.5-5.6") and the line becomes "12-32 mm f/3.5-5.6". The phone's own EXIF reader does not know the LensModel tag, so the app reads it itself.
- The app asks where the zoom is set; entering 32 gives "12-32 mm f/3.5-5.6 - at 32 mm".
- Stowing the lens: the line changes to "Lens not extended - rotate the zoom ring" within a few seconds and stays.
- Extending to 12 mm: the warning clears and the line says "zoom moved, tap to set" (the 32 mm entry is dropped because the focus ring's range changed). Entering 12 gives "at 12 mm".

## Autofocus at 12 mm (Panasonic)

- The sharpness curve peaks around ring 900-1000 of 1570 (`panasonic-12-32-12mm-*.txt`), and the camera's own autofocus is
  unreliable on this lens from far-blurred starts (seeds at 1127-1541).
- **Bug found:** the app read the focus ring's range once per session. On a zoom it changes with the zoom (1570 at 12 mm,
  3837 at 32 mm), so the search ran with the 32 mm range at 12 mm. It is now read again at each autofocus start and every
  few seconds with the lens poll.
- **Bug found:** after the camera's autofocus the lens can still be moving when the climb starts, so the first reading
  shows the old sharp picture, not the position the climb believes it is at (the ring readback just echoes the
  commanded value; the picture lags a ring move by about 300 ms). The stale reading was then chosen as the lock, while the
  real peak sat elsewhere. The climb now compares the reading on arrival at its chosen position with what it had recorded
  there and, if far lower, corrects the record and chooses again.
- After both fixes, starts at 1400 and 2000 lock on the peak (ring 880-1070, 7-12 s) in 9 of 10 trials; one lock at a
  dim-scene shoulder (55% of the peak) remains under investigation.
