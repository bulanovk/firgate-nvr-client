**English** | [Русский](live-camera-panel-sources.ru.md)

# Live camera panel: basis of the browser mockup

Status: the mockup is built and confirmed by the owner; the Android implementation is written and checked on the device. Differences between the mockup and the code are in the [panel review](../live-camera-panel-review.md).

Mockup: [live-camera-panel.html](live-camera-panel.html). Checks: [live-camera-panel.test.cjs](live-camera-panel.test.cjs).

## Studied source

Frigate upstream checkout: `<upstream-checkout>/frigate` (dev @ `ff2cd6a4`). Paths below are relative to this checkout. Whether it matches the PWA version deployed for the user has not been established. The checkout code was not run.

| Scenario | PWA source | Reflection in the mockup |
| --- | --- | --- |
| Single-camera screen | `web/src/views/live/LiveCameraView.tsx` | The video takes the whole screen: a 48 dp header + the frame at the camera's real aspect, black bars above and below. In the app this is the same — a separate panel `feature/live/LiveCameraPanel.kt`. |
| Header | `LiveCameraView.tsx:671-853` | The header lives **outside** `TransformComponent` (`:856-977`), so zoom does not move it. On the left Back and History, on the right fullscreen, picture-in-picture, two-way audio, sound and the gear. |
| Two header layouts | `LiveCameraView.tsx:674-676, 1215, 1673-1684` | In the PWA the layout depends on width: `isDesktop \|\| isTablet` → camera toggles, manual recording and frame snapshot inline in the header, the gear opens a `DropdownMenu`; mobile → the gear opens a `Drawer` (a sheet from the bottom, `max-h-[75dvh]`), and in fullscreen the mobile branch is not rendered at all (`if (fullscreen) return;`). The labels "Назад"/"История" are shown only when `isDesktop` (`:689, 713`). In the mockup, both layouts are switched by the "Широкий экран" button; the camera toggles are moved in the mockup from the wide header into the gear menu — see the differences. |
| Bottom settings sheet | `web/src/components/ui/drawer.tsx` (`vaul` ^1.1.2) | The PWA drawer follows the finger and closes on a downward swipe; `DrawerContent` draws a handle (`mx-auto mt-4 h-2 w-[100px]`). In the mockup the sheet closes on a downward swipe: a long movement closes it, a short one returns it to place. |
| Phone in landscape | `LiveCameraView.tsx:668, 674, 680, 724` | In the PWA on a phone in landscape the header becomes a vertical 48 dp rail on the left (`landscape:h-full landscape:w-12 landscape:flex-col`). The mockup notes this, but has no separate state. |
| PTZ panel | `web/src/components/overlay/PtzControlPanel.tsx` | Under the frame (`absolute inset-x-2 bottom-[10%]`): arrows, zoom, focus and presets (⋮). Drawn only for PTZ cameras. In the mockup the panel is dimmed: the app has no PTZ. |
| Zoom | `react-zoom-pan-pinch` 3.4.4, `LiveCameraView.tsx:93,656-661` | Pinch around a point, drag, mouse wheel, double tap resets. `minScale 1.0`; the PWA does not set `maxScale`. |
| Stream and Mode | `web/src/components/player/LiveStreamSelect.tsx`, `web/src/components/player/StreamTechnologySelect.tsx` (`web/src/components/ui/select.tsx`, Radix Select) | In the PWA these are **drop-down lists**: a full-width trigger with the value and an arrow, and a list of options. "Поток" (stream) — Auto and the camera's streams. "Режим" (`stream.mode`, "Streaming Technology") has exactly **two** options: MSE (always available) and WebRTC (always listed, but dimmed with a reason if unavailable: the browser cannot, it is not configured in `go2rtc.webrtc`, the probe failed, or the video or audio codec is incompatible). `MJPEG` is **not** an engine: in the PWA it is the "Низкая полоса" (low bandwidth) toggle (`stream.lowBandwidth.force`), and the PWA code says so directly: `jsmpeg is the force low-bandwidth switch, not a technology choice` (`LiveCameraView.tsx:267`). The "Режим" block is drawn only for re-streamed cameras with streams and is disabled when debug or low bandwidth is on. The mockup is the same: "Поток" (Auto, Вход · 2K, Вход · 4K, Вход · 720p) and "Режим" (MSE, WebRTC dimmed with a reason) — in the narrow-screen sheet and in the wide-layout menu; the choice changes the value. |
| Frame overlays | `web/src/components/player/LivePlayer.tsx` | A chip of object-type icons at the top left, a motion dot at the top right (pulsing), a 3 px frame during tracking, offline/disabled cards, an error with Retry. The overlays are **inside** the transform — they scale together with the picture. |
| Camera name | `LivePlayer.tsx` | The panel has no name: the PWA shows it only when the camera is offline or disabled. In the mockup the "Состояние" (state) switch shows it. |
| Stream selection | `LiveCameraView.tsx:178` (`Object.values(camera.live.streams)`) | The first stream in config order — the same one the app plays (`FrigateApiParser.kt:16-22`). For the user this is the 2K/4K stream. |
| Archive | `web/src/views/recording/RecordingView.tsx` → `DynamicVideoPlayer` | The mockup has a second screen: the recording in the centre, a floating control panel **outside** the picture (not scaled), the timeline at the bottom. The PWA archive has no zoom — see the differences. |

## Basis of the zoom

Zoom enlarges the **already decoded** frame: the base is the resolution of the stream that the app already plays (2K/4K for the user).

There is no server-side crop: go2rtc has no crop/scale/width/height API for live streams; only a static `ffmpeg:` transform from the Frigate config is possible. So zoom is a geometric enlargement of the frame by the GPU (in the PWA a CSS transform of the `<video>`; in ours a transform of the Compose layer). No new information appears: this is interpolation, not super-resolution. There is no sharpness gain in the PWA or in the plan (`RenderEffect`/`RuntimeShader` require API 33 while minSdk is 31).

The limit is calculated, not set as a number:

```
maxScale = clamp(3 × sourceWidthPx / viewportWidthPx, 1, 10)
```

`sourceWidthPx` is the real width of the decoded frame (`onVideoSizeChanged`), `viewportWidthPx` is the width of the picture on screen in physical pixels. The meaning: no more than about 3 screen pixels per source pixel. The screen of the SM-S901E phone is 1080×2340 at density 480 (360 dp), and the panel picture is 1032 physical pixels: 4K gives 10× (the ceiling), 2K ≈ 7.4×, 1080p ≈ 5.6×, 360p ≈ 1.9×. While the frame width is unknown, the limit is 4×.

In the mockup the limit is calculated in the **physical** pixels of the screen: 360 dp × 3 = 1080 px — the width of the phone screen (SM-S901E, 1080×2340, density 480); the geometry (pinch point, offset) is in the CSS pixels of the element. The counter of the limit and the current scale is in a notes column: the PWA itself has no zoom indicator, and we do not add one to the panel.

## Differences from the PWA

1. **Zoom in the archive.** The PWA has none: `RecordingView` leads to `DynamicVideoPlayer` without a transform (there is only the timeline zoom). In the app the archive zoom is the owner's decision: it is a property of the player, a shared layer over the picture. The recording control panel stays in place.
2. **Zoom limit.** The PWA leaves the upper bound at the library default. We derive the limit from the source resolution — otherwise the user would stretch 190 pixels of 4K over the whole screen.
3. **Still image before the first frame.** In the PWA `showStillWithoutActivity=false` — there is no still, only a spinner. In the app the still is kept as a deliberate decision of the earlier review (`docs/live-design-review.md`).
4. **Microphone, PTZ, low bandwidth, background playback** — not implemented in the app: these are separate features (a WebRTC call, ONVIF, a second engine, a separate player mode). PiP, sound, admin toggles and stream selection are implemented and shown according to camera capabilities and role, as in the PWA. In the mockup unimplemented actions are shown dimmed (disabled) — this demonstrates the potential set of features, not a list of mandatory stubs.
5. **Labels in the wide layout.** In the PWA "Назад" and "История" are labelled only on desktop (`isDesktop`); on a tablet there are no labels. In the mockup the wide layout is shown as desktop, with labels.
6. **Phone in landscape.** In the PWA the header becomes a vertical 48 dp rail on the left; the mockup notes this, but has no separate state.
7. **The wide header in the mockup is shorter than in the PWA.** In the PWA on a wide screen the camera toggles (power, detection, recording, snapshots, audio detection, transcription, auto-tracking) sit directly in the header — there is admin access there. The app has none of these actions, and fifteen buttons in the header would be a wall; in the mockup the toggles are moved into the gear menu, where the PWA keeps them on a narrow screen. The composition of the settings does not change.

## Limits of verification

All frames are synthetic inline illustrations. There is no real video, sound, requests to Frigate or API; playback is not connected. The mockup is an interactive prototype, not a player.

Checked (35 checks): 9 checks of the pure zoom model (limit from resolution, clamps, pinch point, offset bounds, reset); 12 checks of the structure and composition of the mockup (markers of both screens, the narrow header, the composition of the settings sheet per the PWA, the short wide header without camera toggles, the full composition of the drop-down menu, the PTZ panel, the floating row of full screen, the archive control panel outside the transform, the limit in the phone's physical pixels, no external resources); 14 scenarios of the full script on a DOM test double (script syntax, hidden elements, rendering, overlays, pinch, double tap, frame snapshot, sheet/menu by width, fullscreen, archive zoom and tap on the frame, a downward swipe closes the sheet, a short movement returns it, the sheet is limited in height and does not cover the header, "Поток" and "Режим" are drop-down lists with a list of options, the PWA engine list: MSE and a dimmed WebRTC with a reason, MJPEG is not an engine, the wide layout keeps its 720 px width). This is not a real browser and not a check of layout/rendering.

The mockup layout was checked in a browser over the LAN link (geometry measured through the browser DOM): the narrow layout matches the live PWA; the settings sheet at the bottom does not cover the header (the top of the sheet is 160 px below the bottom of the header, with the sheet height 540 px and the screen 750 px); its rows scroll (475 px visible out of 732 px of content); a downward swipe from the handle closes the sheet. The wide layout is drawn at its own 720 px width and is not checked on a narrow screen — it is viewed on a wide display; the header has nine buttons, and the drop-down menu under the gear opens and shows all the settings. After the mockup was confirmed, the app implementation was built and checked on the device — the evidence is in the [panel review](../live-camera-panel-review.md).

The DOM test double found two real defects in the mockup: `attachZoom` read `options.onTap` without a check (a tap on the live frame would crash the page), and the rule `[hidden]{display:none!important}` was missing — the author's `display:flex/grid` overrode the `hidden` attribute, so both screens were drawn at once, and the "Camera offline" card and the "Live video unavailable" strip were shown over the frame. Both defects were fixed, and checks were added for both.

The regressions were first reproduced as RED and, after the fix, turned GREEN. The generation script in the job temp is a historical draft: the final HTML was edited after generation; do not regenerate it over the mockup.
