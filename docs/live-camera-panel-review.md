**English** | [Русский](live-camera-panel-review.ru.md)

# Live camera panel — review against the PWA and zoom as a player property

Date: 2026-10-30, implementation — 2026-11-01.

Status: the mockup look is confirmed by the owner; the Android implementation is written and checked on the device. The "After" column below describes the agreed design; for how the code differs from the mockup, see "What is implemented in Android".

Basis: source reference of the Frigate PWA (`LiveCameraView`, `LivePlayer`, checkout dev @ `ff2cd6a4`), [mockup basis](design/live-camera-panel-sources.md), mockup [live-camera-panel.html](design/live-camera-panel.html). The checkout version is not matched to the PWA deployed for the user; pixel-perfect parity is not claimed.

## Review conclusion

Two differences. First: a tap on a tile in the app opens not a camera screen but the same 16:9 card under a large title — in the PWA it is a separate screen where the video fills the screen, the header lives outside the transform and does not scale, and the picture can be zoomed with a pinch. Second: the app has no zoom at all, and it is needed not only in live but also in the archive — so it is a property of the player, a common layer over the picture, not a feature of the camera screen.

| Before | After |
|---|---|
| A tap on a tile opens a 16:9 card under the title | Camera screen: 48 dp header, video full screen at the camera's real aspect ratio |
| Camera name as a large title above the card | No name in the panel: the PWA shows it only when the camera is offline or disabled |
| No zoom | Player zoom: pinch around a point, drag, double tap — reset; works in live and in the archive |
| No History button for the camera | History in the header opens this camera's archive from the current moment |
| Only Back and fullscreen in the header | The header repeats the two PWA layouts: on a narrow screen — Back and History on the left, fullscreen, picture-in-picture, microphone, sound and gear on the right, the gear opens a settings sheet from the bottom; on a wide screen — the same buttons with "Back"/"History" labels, and the gear opens a drop-down menu with the same settings |
| No fullscreen mode | Fullscreen: video full screen, the header becomes an overlay at the top right |
| Static motion dot | The dot pulses, as in the PWA |
| Aspect buckets 16:9 / 32:9 / 8:9 for both tile and panel | Buckets stay in the grid; the panel uses the camera's real aspect ratio |
| A tap on the archive frame is caught on `ACTION_DOWN` — a pinch also hides the control panel | The tap is handled by a separate clean class: a single finger, no movement, a second tap of a double tap does not count |

## Layout

- Camera screen: 48 dp header + frame at the camera's real aspect ratio, black bars above and below — Fit, as in the PWA on mobile.
- **The header has two layouts — as in the PWA, where the choice depends on screen width, not on the set of actions.**
  - **Narrow screen (phone, portrait):** on the left Back and History as icons, on the right fullscreen, picture-in-picture, microphone, sound and the gear. The gear opens the **camera settings sheet from the bottom** (in the PWA — a `Drawer`): camera toggles (Camera, Object detection, Recording, Snapshots, Audio detection, Transcription, Auto-tracking), Stream and Mode — **drop-down lists** (in the PWA `LiveStreamSelect`, Radix Select: value, arrow, list of options), then Low bandwidth, Manual recording → [Take a snapshot][Start recording], Background playback, Statistics, Debug view.
  - **Wide screen (tablet, desktop):** the "Back" and "History" labels appear, and the gear opens a **drop-down menu** (in the PWA — a `DropdownMenu`) with the same settings as the narrow sheet: camera toggles, Stream and Mode — **drop-down lists**, then Low bandwidth, Manual recording → [Take a snapshot][Start recording], Background playback, Statistics, Debug view. The header keeps only the app's own actions: fullscreen, picture-in-picture, microphone, sound, manual recording, frame snapshot, gear.
  - **Difference from the PWA in the wide layout:** in the PWA the camera toggles sit directly in the header — there they are admin-only. The app has no such actions, and a header of fifteen buttons would become a wall; so the toggles are in the gear menu, not in the header. The settings are not lost — they are in the same set in both layouts.
  - **Phone in landscape:** in the PWA the header becomes a vertical 48 dp rail on the left, buttons in a column.
- Implemented: Back, History, fullscreen, frame snapshot, picture-in-picture, sound, manual recording (admin), camera toggles (admin), stream selection, engine list, statistics and debug view. Not implemented: two-way audio (microphone), PTZ, low-bandwidth mode (JSMpeg) and background playback — in the PWA these are separate features, and we do not have them: two-way audio needs its own API call and WebRTC, PTZ needs ONVIF, low bandwidth needs a second engine, background needs a separate player mode. The app does not draw them: a dead button is worse than no button. The mockup shows them dimmed so that the gap is visible.
- In fullscreen the header leaves the screen and becomes a floating group on the right — as in the PWA (`absolute right-32 top-1`): Back as a filled button, fullscreen and the available actions. On a narrow screen there is no gear in fullscreen — the same as in the PWA (`mobile doesn't show settings in fullscreen view`).
- The tile grid does not change: aspect buckets, the live start order, snapshots and indicators stay as they are.
- Frame overlays are the same as in the tile: the grey chip of object type icons at the top left, the motion dot at the top right, a 3 dp orange frame during tracking, offline/disabled cards, an error bar with Retry.
- Overlays lie **inside** the transform and scale together with the picture (as in the PWA); the header and the archive control panel are **outside** and do not scale.
- The settings sheet from the bottom closes with a **swipe down** (as the PWA `Drawer`): the sheet follows the finger, a short movement returns it to place, a long one closes it.
- The camera name is not shown in the panel — it stays in the grid tiles and appears in the panel only when the camera is offline or disabled.
- Zoom has no buttons, slider or zoom indicator: the PWA has none either. The limit is visible only in the mockup notes.

## Behaviour and ownership

- Zoom is a common layer `feature/player`: the model (`PlayerZoom`) has no Android imports and is JVM-testable; the state and gesture/transform modifiers (`PlayerZoomState`) are shared by all players.
- The zoom base is the **resolution of the already decoded frame**: the width comes from `onVideoSizeChanged` of the live player and the archive HLS player. While the width is unknown, the limit is 4×; after the first frame the limit is recalculated for the actual stream.
- The stream is chosen as in the PWA — the first one in the `live.streams` config order (`FrigateApiParser.kt:16-22`); for the user that is 2K/4K.
- Zoom adds no detail: go2rtc has no server-side crop, the live stream is not re-encoded, and there is no sharpness enhancement in either the PWA or the plan (`RenderEffect`/`RuntimeShader` need API 33 while minSdk is 31).
- The zoom limit follows from the source resolution, not a number chosen by taste: no more than ~3 screen pixels per source pixel.
- The archive control panel and the timeline do not scale; zooming the frame does not move the buttons.
- The tap on the archive frame is moved into a clean class `HistoryFrameTap` (a single finger, no second finger, no movement beyond slop, a second tap of a double tap does not count). `onTouchEvent` still returns `false` — rule D6 is not broken, and the control panel buttons keep receiving their taps.
- Changing the camera and a new source in the archive reset the zoom: a new frame — a new scale.
- The rules of the previous live path do not change: player pool, 500 ms grace, snapshot until the first frame, ON_STOP/ON_START, native MSE video-only.

## Zoom limit

```
maxScale = clamp(3 × sourceWidthPx / viewportWidthPx, 1, 10)
```

`sourceWidthPx` is the width of the decoded frame, `viewportWidthPx` is the width of the picture on screen in physical pixels. The SM-S901E phone screen is 1080×2340 at density 480, that is 360 dp; the panel picture with 8 dp margins is 344 dp = 1032 physical pixels:

| Source | Limit | What is visible at the limit |
|---|---|---|
| 4K (3840) | 10× (ceiling) | an area of ≈384×216 source pixels |
| 2K (2560) | ≈7.4× | an area of ≈344×194 |
| 1080p (1920) | ≈5.6× | an area of ≈344×194, soft |
| 720p (1280) | ≈3.7× | an area of ≈344×194 |
| 360p (640) | ≈1.9× | almost nothing to enlarge |

Why not 20× as on phones: the phone "20×" is a telephoto lens (a separate sensor) + sensor crop + AI reconstruction. A stream from one IP camera has no telephoto lens, no second sensor and no server-side crop; 20× here would mean stretching ~190 source pixels of 4K over the whole screen — the picture falls apart and no new detail appears.

## Fresh checks

- `node docs/design/live-camera-panel.test.cjs` — **35 checks PASS**: the limit from the source resolution (4K → 10× on a phone, 2K → ≈7.4×, 1080p → ≈5.6×), the 4× fallback when the width is unknown, the absolute ceiling of 10×, the pinch point stays under the finger, the frame does not leave its bounds, a drag at 1× moves nothing, reset by double tap, structure markers of both screens, the narrow header (Back, History, fullscreen, gear) and the settings sheet contents per the PWA, the short wide header (no more than ten buttons, camera toggles moved to the menu), the drop-down menu with the full settings set, swipe down closes the sheet (a short movement returns it), the sheet is limited in height and its rows scroll, "Stream" and "Mode" are drop-down lists with the list of options, the PWA engine list (MSE and dimmed WebRTC with a reason; MJPEG is the low-bandwidth switch, not an engine), the wide layout keeps its 720 px width, the PTZ panel under the frame, the gear of features is a gear and not a sun, fullscreen turns the header into a floating row on the right, the archive control panel is outside the transformed picture, the limit in physical phone pixels, no `fetch`/`WebSocket`/external resources.
- 12 smoke scenarios of the full script on a DOM test double: rendering of both screens, PWA overlays (object chip, motion dot, name only when offline, tracking frame, error), a pinch enlarges the frame, a double tap returns 1×, the frame snapshot reports saving, the gear opens the sheet on a narrow screen and the menu on a wide one, fullscreen hides the header, zoom in the archive and a tap on the frame hides the control panel, swipe down closes the sheet, a short movement returns it.
- **The mockup was rendered in a browser over the LAN link** (geometry measured via the browser DOM at 360 dp):
  - the narrow layout gives exactly the header of the live PWA (← and 🕘 on the left; ⛶, picture-in-picture, microphone, sound and the gear on the right; the PTZ row with presets under the frame);
  - the settings sheet opens from the bottom with the gear, **does not cover the header** (the top of the sheet is 160 px below the bottom of the header, sheet height 540 px at screen height 750 px), its rows scroll (475 px visible of 732 px content), swipe down by the handle closes the sheet;
  - the wide layout is drawn at its own width — 720 px, not squeezed to the screen: it is not checked on a narrow screen, it is viewed on a wide display; the header has "Back"/"History" labels and nine buttons instead of fifteen, and the drop-down menu under the gear opens and shows all settings, including "Debug view".
- The DOM test double found two real defects in the mockup: `attachZoom` read `options.onTap` without a check (a tap on the live frame would break the page), and the mockup had no `[hidden]{display:none!important}` rule — the authors' `display:flex/grid` overrode the `hidden` attribute, so both screens were drawn at once, and the "Camera offline" card and the "Live video unavailable" bar sat on the frame. Both are fixed, with checks added for both.
- The mockup was moved to the real phone geometry: 360 dp × 3 = 1080 px (SM-S901E, density 480) instead of the nominal 1440 px.
- There is neither a desktop browser nor jsdom in the environment: the layout check was done through the phone's browser and a DOM double; this is not a check of the production build of the app.
- The list above refers to the mockup checks before the Android implementation; the fresh checks of the app are below.

## What is implemented in Android

The look of the mockup was confirmed by the owner ("ok, write it"), after which the code was written. Differences between the mockup and the code, stated honestly:

| In the mockup | In the app | Why |
|---|---|---|
| Button labels in Russian ("Назад", "История") | English `contentDescription`: "Back to cameras", "Open history", "Fullscreen", "Leave fullscreen", "Camera settings" | The whole app interface is in English; screen descriptions are needed both for accessibility and for instrumentation tests |
| Settings sheet: camera toggles, Stream, Mode, Low bandwidth, Manual recording, Background playback, Statistics, Debug view | Camera toggles (admin), Stream, Mode, Manual recording → [Take a snapshot][Start recording] (admin), Statistics, Debug view | Low bandwidth (JSMpeg) and Background playback are not implemented in the app: they are a second engine and a separate player mode. This is a limitation of the app, not proof that the camera lacks the capability; disabled stubs are not added |
| The wide layout is a separate mockup screen with labels | The same header; labels appear at width ≥ 600 dp (`expanded`) | In the app the width is taken from the window, not from the mockup |
| Fullscreen hides the header, a floating row at the top right | Back with a gradient + leave fullscreen; on a wide screen also the snapshot. No gear in either layout | The app's bottom navigation and system bars are not hidden: for now this is fullscreen of the camera area, not Android immersive mode |
| Swipe down by the handle closes the settings sheet | The standard `ModalBottomSheet` is used | The swipe was not checked manually on the device; the earlier claim that a swipe was impossible was unfounded |
| PTZ panel under the frame, timeline zoom in the archive | Not implemented | There is no PTZ in the app, and no archive timeline zoom either (archive zoom applies to the picture only) |

Files: `feature/live/LiveCameraPanel.kt` (panel and header), `feature/live/LiveCameraSettings.kt` (settings contents), `feature/live/LiveEngineOptions.kt` (engine list), `feature/live/LiveCameraHeaderIcons.kt` (mockup icon outlines), `feature/live/CameraControl.kt` (camera toggles and admin right), `feature/live/ManualRecordingSession.kt` (manual recording), `feature/live/LiveCameraControlRequests.kt` (toggle confirmation by camera activity), `feature/live/LivePlaybackStats.kt` (statistics and debug view), `feature/player/PlayerZoom.kt` and `feature/player/PlayerZoomState.kt` (common player zoom), `feature/player/FrameSaver.kt` (frame snapshot to the gallery, the frame is reduced to 1920 px wide), `feature/live/NativeMseVideo.kt` and `feature/history/HistoryVideoPlayer.kt` (the surface keeps the frame at its source resolution via `SurfaceTexture.setDefaultBufferSize`).

Fullscreen contents: Back, History, fullscreen, frame snapshot, picture-in-picture, sound, manual recording (admin only, in the wide layout), gear. Fullscreen keeps a floating group at the top right: Back, leave fullscreen, PiP, sound, manual recording and snapshot (wide layout); there is no gear in fullscreen in either layout — as in the PWA.

Camera settings (sheet from the bottom on a narrow screen, drop-down menu on a wide one): admin toggles (Camera, Object detection, Recording, Snapshots, Audio detection, Transcription, Auto-tracking — some of them only if enabled in the config), stream selection, engine list, manual recording → [Take a snapshot][Start recording] (the second button is admin), Statistics, Debug view. A viewer gets only stream selection, the engine list, snapshot, statistics and debug view: there are no toggles or manual recording for them.

The fullscreen state is lifted into `LiveCameraScreen`: `BackHandler` first exits fullscreen and only then closes the camera — as `navigate(-1)` in the PWA.

Zoom is wired into the live panel and the archive. The frame resolution comes from `onVideoSizeChanged` of both players; the limit is calculated from it, not set as a number. The picture declares `contentDescription = "Zoom 1.0x"` — this is both accessibility and the marker that a pinch reached the frame.

An expired session in the panel does not throw the user to the sign-in screen: a 401 from manual recording or from a camera toggle leads to `SessionRenewal`, and credentials are deleted only when the server rejects the sign-in itself.

### Header fix after comparison with the mockup (2026-11-01)

The first implementation replaced the mockup outlines with standard Material Icons, left the buttons transparent and used their default sizes. Having the buttons in tests did not prove conformance to the design.

Now the header is 48 dp, buttons 36×36 dp with an 8 dp corner radius, icons 21 dp with a 1.7 outline in a 24 viewport. The SVG paths are moved from the approved HTML into `LiveCameraHeaderIcons`. Button colour `#26282d`, icon colour `#e9e9eb`; Back/History have no fill. The gaps of the left/right groups are 6/3 dp. In the wide layout the frame snapshot is moved into the header before the gear; fullscreen keeps the floating group with the gradient Back and without the gear.

**The set of actions is not fixed by the number of buttons in the demo mockup.** In the original PWA:

- sound — `supportsAudioOutput && preferredLiveMode != "jsmpeg"`;
- microphone — `supports2WayTalk`; an unavailable WebRTC makes the existing button disabled, and does not create a camera capability;
- both flags are computed from `go2rtc/streams/{streamName}` (`detectCameraAudioFeatures`), not from the recording permission;
- PiP depends on the platform and the player mode; admin toggles depend on the role, some of them also on `enabled_in_config`;
- the gear is hidden in fullscreen on both narrow and wide screens (`LiveCameraView.tsx:1349`).

Android now requests this metadata: `liveStreamInfo` reads `go2rtc/streams/{streamName}` and exposes `audioOutput` and `twoWayAudio`. The sound button appears only when the stream has declared audio output; the audio track is off by default (`setAudioEnabled`) and is turned on by the button, and if no audio is decoded within 5 s, the track is turned off again and the user gets "This camera has no audio". So the button follows the camera's capabilities, not its recording permission. Two-way audio (microphone) is not implemented: the metadata is read, but there is no feature — the button is not drawn. Actions missing from the Android app are still not shown as stubs.

Fresh checks of the fix:

- RED: three checks revealed a 40 dp size instead of 36, the absence of the snapshot in the wide header, and an extra gear in the wide fullscreen;
- GREEN: all 19 `LiveCameraPanelTest` pass, including sizes, fill/corner radius by screenshot, the wide layout, sound, PiP, manual recording, the engine list and the absence of stubs;
- full JVM suite — **387 tests, 0 failures**; `assembleDebug` succeeds; **35 checks of the HTML mockup** pass;
- full instrumentation suite on the Samsung — **37 tests PASS**, installed only with `adb install -r`, run through `am instrument`;
- in the app itself a camera was opened, a decoded MSE frame was received, fullscreen was opened with the button; headers were inspected on fresh screenshots. The current runtime log had one repeated BUFFERING → READY, so the absence of rebuffering is not confirmed by this smoke test;
- [normal header on the device](design/live-camera-header-device-20261101.png) and [fullscreen header](design/live-camera-fullscreen-header-device-20261101.png) — header crops only, without camera frames;
- the wide branch is checked by Compose tests, but no device with width ≥600 dp was checked. Saving to the gallery and the sheet swipe were not checked manually. Zoom and authorisation were not changed by this fix.

### Mode (engine) — per the PWA reference

The "Mode" list was reworked after checking the PWA sources: `StreamTechnologySelect.tsx:43-102` gives exactly two options — MSE and WebRTC. MSE is always available; in the PWA WebRTC is also always in the list, but dimmed and with a reason if the engine is unusable (`use-webrtc-availability.tsx`: browser, missing `go2rtc.webrtc.candidates`/`ice_servers`, unavailability, video codec, audio codecs).

MJPEG is not an engine: in the PWA it is the "Force low-bandwidth mode" switch (`LiveCameraView.tsx:267`, code comment: `jsmpeg is the force low-bandwidth switch, not a technology choice`). So it is removed from the engine list and remains as a separate settings row, which the app does not have.

The "Mode" block in the PWA is drawn only for cameras with a restream and at least one stream, and is blocked in debug mode and in low bandwidth. In the app the list is always available: the camera has one stream, and there is no low-bandwidth mode.

In the mockup and in the app the list is the same: MSE is available, WebRTC is dimmed with the reason "The app has no WebRTC playback yet" and cannot be selected. The reason is visible to the user: the choice exists, but it is not really available while the app has no WebRTC playback path. The list is defined by the pure function `liveEngineOptions()` (`feature/live/LiveEngineOptions.kt`) — JVM-testable, without Android imports.

## Decisions taken

1. **Zoom is a property of the player, not a feature of the live screen.** The owner's decision. Cost: the common layer lives separately from the screens, and it must be wired into every new player (Events will get zoom automatically when it has its own player).
2. **The limit is derived from the source resolution, not set as a number.** Cost: the limit changes with the stream — at 360p zoom is effectively unavailable. This is honest: there is nothing to enlarge.
3. **Zoom without sharpness enhancement**, exactly as in the PWA. Cost: at the limit the picture is soft; softness is a property of the source.
4. **The camera name in the panel is hidden** (the PWA shows it only when offline/disabled); it stays in the grid tiles. Cost: in the panel one has to remember which camera is open — as in the PWA.
5. **Fullscreen is a separate state with an overlay header** at the top right. Cost: one more screen state and its preservation on rotation.
6. **Mockup first, Android code after the look is confirmed.** Cost: the Android part was postponed until the owner's answer.

## Differences from the PWA

1. **Archive zoom** — the PWA has none (`RecordingView` → `DynamicVideoPlayer` without a transform; it has only timeline zoom). For us, archive zoom is the owner's decision.
2. **Upper zoom bound** — the PWA keeps the library default; for us the limit is derived from the source resolution.
3. **Still image before the first frame** — in the PWA `showStillWithoutActivity=false` (spinner only); in the app the still is kept as a deliberate decision of an earlier review ([live-design-review.md](live-design-review.md)).
4. **Microphone (two-way audio), PTZ, low bandwidth, background playback** — not implemented in the app. PiP, sound, admin toggles and stream selection are implemented and shown according to the camera's capabilities and the role, as in the PWA. The dimmed elements in the mockup demonstrate a potential feature set, but do not define a mandatory list of disabled buttons. The PWA hides actions according to the camera/player capabilities and the role; the app's implementation limitation must not be presented as the camera lacking the capability.
5. **Header layout** — the PWA has two, chosen by screen width: narrow (settings sheet from the bottom) and wide (drop-down menu). Both are reproduced in the mockup; the "Wide screen" switch above the mockup toggles the layout. There is one difference from the PWA: the PWA draws the "Back" and "History" labels only on desktop, while the mockup shows them in the wide layout as on desktop.
6. **The wide header in the app is shorter than in the PWA** — in the PWA the wide screen header holds the admin camera toggles, manual recording and snapshot. In the app the admin toggles are in the gear menu (fifteen in the header would become a wall), while manual recording and snapshot are in the header — per the approved mockup, in the wide layout. This is a functional limitation, not a design change and not a sign that the camera lacks the capability.
7. **Engine list** — in the PWA WebRTC is selectable if it is available; in the app WebRTC has no playback path, so it stays in the list dimmed, with a reason. This is the same behaviour as the PWA for an unavailable engine, only the reason differs: not "the browser cannot", but "the app has no WebRTC path yet".

## Out of scope

- Object boxes in the live video (the PWA has none).
- An own player in the Events section — a separate task ("entry into the History screen from the event list").
- Switching the stream to a higher resolution on zoom (`LiveStreamSelect` in the PWA).
- Sharpness enhancement (AI/super-resolution).
- Two-way audio (microphone), PTZ, low-bandwidth mode (JSMpeg), background playback.
- Wi-Fi routing, continuous playback.
