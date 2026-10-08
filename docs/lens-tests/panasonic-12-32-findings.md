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
