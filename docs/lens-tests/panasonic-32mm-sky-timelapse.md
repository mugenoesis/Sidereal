# Unattended sky timelapse, Panasonic 12-32mm at 32 mm (2026-10-08)

A long run to see whether the app can shoot unattended through sunset. Stopped by hand at 138 of 240 frames because
the footage had gone too dark; nothing failed.

## Setup

- Camera on the Osmo handle, lens at the 32 mm mark, entered as 32 mm in the app, manual exposure at f/5.6 (the camera's
  program mode would have chosen f/4 and hung), manual focus at ring 3480 (the peak on houses about 30 m away; the sky
  itself has nothing to focus on), aimed out of a window at the sky (pitch -18, yaw -100).
- Timelapse: 240 frames, one a minute (4 hours), day-to-night ramp on (keep darkness 50%, max ISO 3200), video at the end.
- Sunset about 18:30 local; the run went 16:58 to 19:17 (138 frames), ending in nautical twilight.

## What happened

- 138 frames, one a minute, no failed shots, no retries, no warnings in the log. The gimbal was re-aimed before every frame
  and was within 0.2 degrees every time. The phone stayed connected to the camera and the camera battery read 100% throughout.
- The handle never went to sleep by itself in the 2h20m, so the wake path was not exercised by the run. It was tested
  separately by putting the handle to sleep from the phone in the middle of a short timelapse: the app woke it, waited for the
  camera, re-aimed and finished all frames.
- Exposure followed the light, but only half of it, as set: the scene fell by about 7 stops and the exposure rose by 3.7
  (1/320 at ISO 100 to 1/25 at ISO 100). The ramp lengthens the shutter first and uses ISO only once the shutter is at its
  limit (56 s at this interval), so ISO never moved.
- Mean frame brightness (0-255) went from about 60 at the start to about 6 at 19:15: too dark to be useful, hence the stop.

## What to change

1. **Start from a properly exposed frame.** The ramp judges everything against the first two frames. This run began already
   dim (the light fell while the rig was being set up, and the 1/125 exposure that was asked for did not take, so the run began
   at 1/320). A baseline at mid-grey would have given brighter footage throughout. The sequence could meter first and set a
   sensible starting exposure, or at least warn when the first frame is much darker or brighter than mid-grey.
2. **Lower "keep darkness" for a sky at night.** At 50% the night comes out about half as dark as it was, which is dark when the
   start was already dim; 0-25% keeps the sky readable. This is a setting, but a night-sky preset would help.
3. A first attempt began with a clipped frame (sky at 247 of 255), which would have thrown the ramp's light estimate off by
   a stop or two; the setup should check for a clipped or very dark baseline.
4. The `exposure` debug command silently failed to set 1/125 at ISO 100 once; not yet understood.

## Not covered

The 138 frames are on the camera's card and were not downloaded; no video was made. A run to full darkness with a better
baseline is the open test.
