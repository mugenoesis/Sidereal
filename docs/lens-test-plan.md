# Lens test plan

Sidereal was built and tested with one lens, the **DJI MFT 15mm f/1.7**. Three more are lined up, chosen because
together they cover most of what the app's lens-dependent features need to handle:

| Lens | Why it is a good test |
|---|---|
| **Olympus M.Zuiko 45mm f/1.8** (prime, about 21 x 16 degrees on this sensor) | A long, fast lens. Shallow depth of field means a narrow autofocus peak; a narrow view stresses panorama planning, dither size, stitching and the face tracker's gains. |
| **Olympus M.Zuiko 14-42mm f/3.5-5.6 EZ** (power zoom, about 60 down to 23 degrees) | A zoom, so the camera cannot say where it is set; and a variable aperture, so the widest aperture changes with the zoom. |
| **Panasonic Lumix G Vario 12-32mm f/3.5-5.6** (mechanical zoom ring, collapsible, about 73 down to 30 degrees) | **On hand, so it goes first.** A wide zoom with a variable aperture and a plain mechanical zoom ring, so a focal length can be set exactly and held. It is the cheapest way to prove the zoom handling (the zoom warning, the *Focal length* option, the stitcher's EXIF correction) before the Olympus lenses arrive. |

Both are Micro Four Thirds lenses the Zenmuse X5 is expected to support. **Confirm that first** (section 1), because
the camera, not Sidereal, decides what it will talk to.

## What changed in the app for this

The app used to assume 15 mm in several places. It now reads the lens name from the camera and uses the focal length
for panorama planning, dither size, the stitcher's starting field of view and the face tracker's gains, and the ramp
opens to whatever widest aperture the camera accepts. This plan checks those on real glass. Expected behaviour:

- **45mm:** the lens is reported as a prime at 45 mm, so everything adapts by itself (*Focal length: Auto*).
- **14-42mm:** the lens is reported as a zoom (14-42 mm). The panorama options then show a warning and plan for 14 mm
  until you set the *Focal length*; the stitcher then corrects itself from the focal length stored in each photo.

## Session 1: the Panasonic 12-32mm (do this first)

The lens is on the camera from the start and the zoom ring is moved one step at a time, in this order. The ring is marked
**stowed, 12, 14, 18, 25, 32** (there is no 20 mark). One person moves the ring, the other runs the commands; wait for
the "move it now" before each move.

| Step | Ring at | Why |
|---|---|---|
| 0 | **Stowed** (lens retracted, camera on) | A real use case: a Lumix body says "please rotate the zoom ring to extend the lens". What does the camera tell the app, and what should the app tell the person? |
| 1 | **12** | Widest; f/3.5. First full run. |
| 2 | **14** | A mark close to 12: do the numbers actually move? |
| 3 | **18** | Mid. |
| 4 | **25** | Mid-long. |
| 5 | **32** | Longest; the widest aperture has dropped to about f/5.6; face tracking check. |

At **every** step run `tools/lens_step.sh panasonic-12-32-<step>` (about a minute). It writes down every value the camera
will give (335 keys and the pushed camera state), takes one photo and reads the focal length, aperture and lens name
out of its EXIF. Diffing the dumps between steps shows whether the camera exposes the zoom position at all, and the
EXIF line says what the lens really is at each mark.

**Step 0 asks specifically:** does `LENS_IS_INSTALLED`, `HAS_ERROR`, the focus status, the live view or a photo attempt
behave differently when the lens is stowed? Does the camera refuse to shoot? Does the lens name still come back? Whatever
it reports is what the app can use to warn "lens not extended" the way the camera body does.

**Steps 1, 3 and 5 also get** `tools/lens_test.sh panasonic-12-32-<mm>mm` (about four minutes: apertures accepted, the
sharpness curve, autofocus), and a panorama with *Focal length* set to the mark, then one at 32 with it left on *Auto*.

**Can the app show the real focal length for every lens?** The camera does not report the zoom position live (the lens
name is fixed text and the optical-zoom calls say "unsupported"). Every photo does carry the true focal length in its
EXIF, so the options are, in order of effort: read it from the last photo after a sequence is downloaded (free, already
partly done in the stitcher); remember the last value per lens; or measure it from the live view by turning the gimbal a
known angle and seeing how far the picture moves. This session decides which is worth building: the dumps tell us if a
live source exists, the EXIF lines tell us how accurate the lens' own number is.

What good looks like at the end: the lens is reported by name with "12-32mm"; f/3.5 accepted at 12 mm and about f/5.6 at
32 mm; the EXIF focal length is close to each mark; panoramas stitch without gaps with *Focal length* set to the mark, and
the stitcher corrects itself when left on *Auto*; autofocus locks within about 10 s at 12, 18 and 32 mm.

## 0. Before you start

- Mount the lens with the camera **off**; switch on; wait for the live view.
- **Balance the gimbal** for the new lens (the 45mm is front-heavier; the EZ is a light pancake) and run the gimbal's
  own auto-tune/calibration. A badly balanced gimbal will make every gimbal test below look like a software bug.
- Power-zoom lens: extend it to a working position before shooting; it retracts when the camera sleeps.
- Use a debug build of the app on the phone (the checks use its adb harness), battery above 50%, camera card with room.
- Pick a scene with fine detail at 2-5 m, well lit (a bookshelf is ideal), and a second one outdoors at a distance.

## 1. Does the camera accept the lens? (10 minutes)

Run the automated battery, which writes a text report you can paste into `docs/lens-tests/`:

```
tools/lens_test.sh olympus-45mm-f1.8
tools/lens_test.sh olympus-14-42-ez-at-14mm      # then again with the lens zoomed to 28 and to 42
```

It records, for the lens on the camera: the lens name the camera reports; which apertures the camera accepts; the
sharpness over the whole focus ring; and the software autofocus' time to lock from four blurred starts.

| # | Check | Expect | Record |
|---|---|---|---|
| 1.1 | Lens name in `LENS info=` | A readable name with the focal length ("...45mm F1.8" / "...14-42mm F3.5-5.6 EZ"). If it is empty or odd, the app falls back to 15 mm: **that is a bug to fix** (see the parser in `camera/LensInfo.kt`). | The exact string |
| 1.2 | Live view | Picture, correct orientation. | |
| 1.3 | `LENS interchangeable=true`, `adjustableAperture=true` | Both true. | Anything else |
| 1.4 | Apertures accepted | 45mm: f/1.8 down to f/22. EZ: f/3.5 at 14 mm, f/5.6 at 42 mm, to f/22. The camera refuses the impossible ones ("Param Illegal"). | The accepted range, at each zoom |
| 1.5 | EXIF of a test photo | `FocalLength`, `FNumber` and `LensModel` present and correct. | |

## 2. Per-feature checks

### 2.1 Panorama and stitching

1. Open the panorama options. **45mm:** *Focal length* shows *Auto*; the summary line should end "... grid - 45 mm".
   **EZ:** a warning about the zoom appears; the summary says 14 mm.
2. **EZ:** set the zoom to 14, then 28, then 42, and for each set *Focal length* to match (the stepper starts from 15).
3. Shoot a 120-degree panorama (45mm: expect around ten columns; the frame count and download time are in the summary).
4. In the log, find `PanoramaProcessor: aligned on N matches: fov xS` (use `adb logcat -s PanoramaProcessor`).

| Check | Expect |
|---|---|
| Planned frames vs lens | Longer lens = many more frames; the overlap is as chosen (30% default). |
| Stitch result | One clean picture, no gaps between frames. Gaps mean the plan used the wrong focal length. |
| `fov xS` | Close to 1.0, roughly 0.9-1.0. Far from that on the same lens means the field of view is wrong. Compare with the 15mm's (about 0.93). |
| EZ with *Focal length* left on Auto | The stitcher logs "frames were shot at X mm, planned for Y mm" and still stitches. |
| 45mm panorama with the nearest object about 1 m away | Small parallax seams are normal (the gimbal turns about its own axes, not the lens). |

### 2.2 Autofocus

`tools/lens_test.sh` gives the real sharpness curve (`AFCURVE`) and four autofocus runs (`AFTRIAL`). The software
autofocus was tuned on a broad hill (the 15mm's peak is about 400 ring units wide). The 45mm at f/1.8 will have a much
**narrower** peak.

| Check | Expect |
|---|---|
| Curve shape | One peak. Note its width (ring units where sharpness is above half the maximum). |
| Lock time, quick climb | About 5-8 s, as on the 15mm. |
| Lock accuracy | Locked ring within a few percent of the sharpness maximum in the curve. |
| **Risk (45mm):** the climb's first step is 5% of the ring (about 100 units). A peak narrower than that can be stepped over. | If locks are poor or inconsistent, the fix is to scale the climb's step with the lens (smaller on a fast long lens): `probeFraction` in `SmoothFocusClimb.Config`. Capture the `AFCURVE` line so it can be tuned offline. |
| EZ | Repeat at 14, 28 and 42 mm. The curve moves and narrows towards 42 mm. Zooming while locked should make it search again (scene changed). |
| Star focus assistant | Point at a bright light; FWHM reading should reach a clear minimum at best focus. |

### 2.3 Exposure and the day-to-night ramp

| Check | Expect |
|---|---|
| P / A / S / M | All four modes set and read back; aperture row works (A and M). |
| Ramp start | `adb logcat -s RealSequenceHost` shows `aperture ... -> F_1_DOT_8` (45mm) or `F_3_DOT_5` (EZ at 14 mm), and the shutter is shortened to match. The first frame's brightness matches the scene. |
| EZ | At 42 mm the widest is f/5.6: the ramp should log some `rejected` lines and then accept `F_5_DOT_6`. Zooming during a ramp changes the lens' widest aperture and so the exposure: note how the camera behaves. |
| Brightness range | The ramp's dark limit (shutter, ISO) is the same, so with f/5.6 it reaches darkness a stop or two sooner than with f/1.8: expected. |

### 2.4 Gimbal, dither and face tracking

| Check | Expect |
|---|---|
| Dither (intervalometer, dither on) | The picture shifts by a few dozen pixels between frames on either lens (about 0.1 degrees on the 45mm, about 0.5 on the 15mm). |
| **Face tracking, 45mm** | Follows without hunting. The tracker's gains are scaled to the field of view (about a third of the 15mm's). If it oscillates, record the log (`FaceTrackingController` lines) so the scale can be tuned. |
| **Face tracking, EZ** | The app cannot know the zoom, so it uses the 15mm's gains at every zoom. At 42 mm expect it to be too aggressive. Note at which zoom it starts to oscillate. (Fix if so: make *Focal length* a global setting that also feeds the tracker.) |
| Timed A-B move, joystick | Unchanged by the lens; only check that they feel right at 45 mm (a narrow view makes small gimbal errors obvious). |

### 2.5 Timelapse, series download, video

These are lens-independent, so one quick run per lens is enough: a 12-frame timelapse with *Make video* on, and check
the folder, file names and video as usual. The EXIF focal length in the downloaded JPEGs should match the lens.

## 3. Reporting

Save each run as `docs/lens-tests/<lens>.md`, from the template below, including the raw `tools/lens_test.sh` report.
Do not add photographs of your surroundings.

```
# <lens>
- Date, app version, firmware, camera, mount adapter (if any)
- Lens string reported by the camera:
- Works at all? (live view, focus, aperture)
- Accepted apertures:
- Autofocus: peak width, lock times (quick climb), lock accuracy
- Panorama: focal length used, frames, stitch fov scale, any gaps
- Ramp: aperture it opened to, first-frame brightness
- Face tracking: stable / oscillates (and at which zoom)
- Problems and what you would change
```

## 4. Pass criteria

A lens is **supported** when 1.1-1.5 are fine, a panorama stitches with no gaps, the ramp opens to its widest aperture
and holds the first frame's brightness, and the autofocus locks within about 10 s and within about 10% of the
sharpest focus in the curve. Anything else is listed as a known limitation in the README's *Lenses* section, with the
workaround.

## 5. Likely problems to look for

- **Flicker.** Under LED lamps or a monitor a slow shutter makes the preview flicker. The autofocus normalises for that,
  but test in steady daylight first so a lens problem is not confused with a lighting problem.
- The camera reports a name the parser does not understand (fallback to 15 mm; fix the parser).
- A fast long lens' narrow autofocus peak is stepped over (smaller steps).
- A variable-aperture zoom changing its widest aperture mid-sequence (document it; do not zoom during a run).
- The face tracker over-correcting on a zoom (make the lens a global setting).
- The EZ retracting between shots if left idle (keep the camera awake; note it).
