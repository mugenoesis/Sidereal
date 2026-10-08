---
title: Sidereal privacy policy
---

# Sidereal privacy policy

Effective 8 October 2026.

Sidereal is a free, open source app that controls a DJI Osmo Pro camera and gimbal from an Android phone, with an optional Wear OS watch remote. This page says what the app does with information. The short version: **the developer collects nothing**. There are no accounts, no analytics, no advertising and no tracking in the app, and nothing you shoot or record leaves your devices because of Sidereal.

## What stays on your devices

- **Photos, videos and panoramas.** Files you download from the camera, and panoramas or timelapse videos the app makes from them, are saved on your phone in `Pictures/Sidereal` and `Movies/Sidereal`. They are never uploaded by the app.
- **Audio.** If you choose to record audio on the phone alongside a video, it is saved on your phone. The microphone is used only while you are recording.
- **Settings.** Your choices (for example button mappings, the Osmo's WiFi password if you enter it, and display options) are stored on your phone.
- **Face tracking.** If you turn it on, faces in the camera's live view are found on your phone using Google's on-device ML Kit. The pictures are not stored or sent anywhere.

## Permissions and why

- **Location** - Android only lets an app read the name of the WiFi network it is connected to if it holds the location permission. Sidereal uses it for that and nothing else: to tell whether you are on the Osmo's WiFi. Your location is never read, stored or sent.
- **Nearby devices (Bluetooth)** - to show the name of a Bluetooth microphone you pick for audio recording.
- **Microphone** - to record audio when you ask it to.
- **Notifications** - to show progress while a long timelapse or panorama runs, and when it has finished.
- **Wake lock, foreground service, WiFi/network state** - to keep a long sequence running with the screen off and to talk to the camera.

## Other software in the app

- **DJI Mobile SDK.** Sidereal uses DJI's SDK to talk to the camera. The first time it runs (and occasionally afterwards) the SDK contacts DJI's servers to register the app, which is why the first launch needs the internet. The SDK may send technical information about the app and the phone to DJI as described in [DJI's privacy policy](https://www.dji.com/policy). Sidereal's developer does not receive any of it.
- **Google Play services (Wear OS Data Layer).** If you use the watch remote, the live view pictures and commands travel between your phone and your watch through Google Play services on those two devices. They do not go to the developer.
- **Google ML Kit (face detection).** Runs on the phone, as above.

## Children

Sidereal is not directed at children and does not knowingly collect any information from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new effective date.

## Contact

Questions or concerns: open an issue at [github.com/mugenoesis/Sidereal/issues](https://github.com/mugenoesis/Sidereal/issues).
