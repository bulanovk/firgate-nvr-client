**English** | [Русский](README.ru.md)

# Frigate Android

An Android app that shows cameras and events from a [Frigate NVR](https://frigate.video) video surveillance server.

This is an independent client. It is not part of Frigate and is not developed by its authors; you need your own Frigate server to use it.

## Features

- **Live** — a grid of cameras in real time. Tap a camera to open it full size, with sound (if the camera provides it) and pinch-to-zoom.
- **Alerts on top** — a carousel of unreviewed alerts from the last hour. Tap a card to open its recording in the archive.
- **Archive** — camera recordings with a timeline and playback speeds from 0.5× to 16×.
- **Events** — a list of events with filters by object and zone.
- **Sign in once** — the app remembers the server and renews the session without asking for your password again.

## Requirements

- Android 12 or newer.
- A running Frigate server reachable from the phone (home network, VPN, or the internet).
- A Frigate account with permission to view cameras.

## Installation

There is no store build yet. To build from source:

1. Install JDK 21 and the Android SDK.
2. From the project root, run `./gradlew assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on your phone.

On first launch, enter the address of your Frigate server and sign in with your account.

## Frigate NVR

Frigate is free, open-source video surveillance software. It records cameras and uses AI to detect people, cars, and other objects in the video. Documentation is at [frigate.video](https://frigate.video), and the source code is at [blakeblackshear/frigate](https://github.com/blakeblackshear/frigate).

## Contributing

- How to contribute: [CONTRIBUTING.md](CONTRIBUTING.md).
- Feature design, protocols, and device checks: [docs/](docs/).
