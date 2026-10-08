# Publishing Sidereal on Google Play

Everything here is prepared in the repository; this page says what to build and what to type into Play Console.
Listing text is in `store/listing/en-GB/`, the icon and feature graphic in `store/graphics/` (drawn from the app icon by
`python3 store/make_graphics.py`; no photographs), and the privacy policy in `docs/privacy-policy.md`.

## 1. Build the bundles

```
export JAVA_HOME=~/.jdks/jbr-17.0.14
./gradlew :app:bundleRelease :wear:bundleRelease
```

- Phone: `app/build/outputs/bundle/release/app-release.aab`
- Watch: `wear/build/outputs/bundle/release/wear-release.aab`

Both are signed with the key in your local `keystore.properties`. The phone and watch apps share the package name
`io.github.mugenoesis.sidereal` (that is what makes them one product on Play); upload the phone bundle to the
**Phone/Tablet** form factor and the watch bundle to **Wear OS**. Each needs its own `versionCode`, higher than the
last upload for that form factor (the repo bumps both together).

## 2. App signing - decide this first, it cannot be undone

Play re-signs what you upload with an *app signing key*. Two ways to set it up:

- **Use the existing key (recommended).** In Play Console > App integrity > App signing, choose *Use an existing key*
  and upload it with Google's `pepk.jar` (the console shows the exact command). The Play version and the GitHub
  version are then signed by the same key: the Wear OS link between a Play phone and a GitHub watch (or the reverse)
  keeps working, and people can move between the two without uninstalling.
- **Let Google generate the key.** Then your current key is only the *upload key*, the Play and GitHub APKs have
  different signatures, they cannot update each other, and a phone from one channel will not pair with a watch from
  the other (the Wearable Data Layer checks the signature).

## 3. Before the first upload: the 16 KB page-size requirement

Apps that target Android 15 or newer must have their native libraries aligned for 16 KB memory pages. DJI's SDK
(4.16.4, and 4.18 which I checked too) ships 17 libraries whose first two code segments are 64 KB-aligned but which
carry a small third segment aligned to only 4 KB (and `libDJISDKLOGJNI.so` is 4 KB throughout). Whether Play blocks
the upload depends on how strict its check is. Upload the bundle to **Internal testing** first and read the
*App bundle explorer* page: if it says the app does not support 16 KB, the options are limited (the libraries are
DJI's binaries and cannot be rebuilt here).

## 3a. Closed testing first

A new personal developer account normally has to run a closed test with a minimum number of testers for a minimum
number of days before it can apply for production access. Check the current numbers in Play Console (Dashboard >
*Set up your app*); plan on about 12 testers for 14 days.

## 4. Main store listing

| Field | Value |
|---|---|
| App name | contents of `title.txt` |
| Short description | `short_description.txt` |
| Full description | `full_description.txt` |
| App icon | `graphics/icon-512.png` |
| Feature graphic | `graphics/feature-graphic-1024x500.png` |
| Phone screenshots | not included - take your own (see below) |
| Category | Photography (app) |
| Contact email | (yours - shown publicly) |
| Website | https://github.com/mugenoesis/Sidereal |
| Privacy policy | URL of `docs/privacy-policy.md` once published (see below) |

Wear OS needs at least one square watch screenshot under *Wear OS* in the listing.

**Screenshots.** Play wants 2 to 8 phone screenshots, longest side no more than twice the shortest, so the app's
2340x1080 screen has to be padded to 2340x1170 (the app's background is black, so padding is invisible):

```
adb exec-out screencap -p | ffmpeg -i - -vf "pad=2340:1170:0:45:black" shot.png
```

They are not in the repository on purpose: the live view shows whatever the camera points at, so take them with the
camera pointed at something you are happy to publish (the sky, a plain wall, a landscape).

**Privacy policy URL.** The simplest public address is GitHub Pages: repository Settings > Pages > Deploy from a
branch > `main` / `/docs`. The policy is then at `https://mugenoesis.github.io/Sidereal/privacy-policy`.

## 5. App content declarations

- **Privacy policy:** the URL above.
- **Ads:** no ads.
- **App access:** *All or some functionality is restricted.* The app only works with a DJI Osmo Pro camera. Reviewer
  note to paste:

  > Sidereal is a remote control for a DJI Osmo Pro (Zenmuse X5) camera over that camera's own WiFi. Without the
  > camera the app opens to a "Not on your Osmo's WiFi" screen and nothing else can be exercised. A screen recording of
  > the app connected to the camera and shooting a sequence is at: <video URL>. No login is needed.

- **Content rating:** the questionnaire for a utility / photography app: no violence, sexual content, language,
  controlled substances, gambling, user-generated content or sharing of location. Expect the lowest rating.
- **Target audience:** 18+ (or 13+); not for children. No "Designed for Families".
- **Data safety:** see section 6.
- **Government / financial / health / news apps:** none apply.
- **Advertising ID:** the app does not use it (it is not in the manifest).
- **Foreground service permission** (`FOREGROUND_SERVICE_CONNECTED_DEVICE`): Play asks for the type, a description
  and a short video. Type: *Connected device*. Description to paste:

  > While a timelapse, panorama or other long shooting sequence runs, the app keeps a foreground service (with a
  > notification showing progress and a Stop button) so Android does not stop it when the screen turns off. The
  > service holds a wake lock and a WiFi lock to keep the WiFi connection to the camera (a DJI Osmo Pro) alive and to
  > keep sending shutter and gimbal commands on schedule, which can run for several hours. It starts when the user
  > presses Start on a sequence and ends when the sequence finishes or the user stops it.

  Video: screen-record starting a sequence, switching the screen off, the notification progressing, and Stop.
- **Permissions that ask for a declaration:** none of the restricted ones (no background location, no all-files
  access, no photo/video read permissions, no SMS/call log). Location is a normal runtime permission, used only so
  Android lets the app read the WiFi network name.

## 6. Data safety form (draft answers)

Sidereal has no server of its own, so the developer collects nothing. Play's definition of "collected" is data sent
off the device, which excludes everything the app stores locally.

- *Does your app collect or share any of the required user data types?* Yes, but only through the DJI SDK:
  - **Device or other IDs** - sent to DJI's servers when the DJI SDK registers the app (the app key and device
    information). Purpose: app functionality. Shared with a third party (DJI): yes. Collection is required (the app
    does not work without registration).
  - Everything else: **no**. Photos, videos and audio stay on the device; location is not read or sent; face
    detection runs on the device.
- *Is all data encrypted in transit?* Yes for the registration call (HTTPS) - confirm against the DJI SDK
  documentation, because the SDK is closed source and its exact traffic was not audited.
- *Can users request that data is deleted?* There are no accounts and the developer holds no data; say so. (DJI's own
  policy covers the registration record.)

If DJI's SDK documentation lists more data than this, add those types: the form has to describe what the SDK does,
not just what the app's own code does.

## 7. After the first review

- Keep `versionCode` rising (`app/build.gradle`, `wear/build.gradle`).
- GitHub releases and Play releases can share the same `versionName`.
- The watch app and phone app should be released together; the watch is only a remote.
