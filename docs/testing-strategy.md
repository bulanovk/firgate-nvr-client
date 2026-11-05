**English** | [Русский](testing-strategy.ru.md)

# Frigate Android testing strategy

Updated: 2026-11-03. The current playback path is native go2rtc MSE / Media3, video-only. The app has no WebRTC playback. The second playback path is the HLS archive (Media3 `HlsMediaSource`) on the History screen; it is not tied to the live stream.

This document defines criteria; it does not declare them met. Results of the current change: [Live design review](live-design-review.md), [verification of the carousel and History](live-review-history-verification.md). API contract: [Frigate Live protocol](frigate-live-protocol.md).

## 1. Stack and limits of evidence

| Level | Tool | What it confirms |
|---|---|---|
| JVM unit | JUnit 5/Jupiter, JUnit Platform | Parsing, reducer, policy, session/output identity, encryption |
| Transport integration | OkHttp MockWebServer | HTTP paths/cookies, WebSocket envelopes, MIME negotiation, byte order, close/errors |
| Compose UI | BOM-aligned ui-test-junit4, AndroidJUnitRunner (JUnit 4) | Semantics, state-driven overlays, snapshot visibility, Retry action |
| Physical device | Native MSE + ADB, fresh PID/time/screenshots | The first frame actually shown, compositing, sustained playback, lifecycle release |
| Black-box smoke / acceptance | UiAutomator (module `blackbox-tests`) + mock Frigate (`blackbox/`, [README](../blackbox/README.md)) | Full user path and system boundaries: login and session renewal, Live, History, video (a decoded synthetic H.264 frame), sound, picture-in-picture, pinch, rotation, Events (list, card preview, session, Motion). 33 tests run on a device on 2026-11-03 |

Compose component tests live in `app/src/androidTest` and receive presentation state (tile, camera panel, zoom, the Events screen, its gestures, mutations and motion region). The end-to-end "login → transport → screen" scenarios are run by UiAutomator against the mock Frigate; screenshot goldens are not implemented. Nothing may be declared green based on the JVM suite or on the app launching. From outside the process UiAutomator sees the screen, requests to the mock and system boundaries (background, a held decoder in `dumpsys media.resource_manager`), but it does not replace Compose checks of semantics and state.

## 2. JVM and transport

### Data and policy

- ServerProfile: URL validation, origin/base path, rejection of credentials/query/fragment.
- REST config/profile/events: defaults, optional fields, disabled cameras, detect dimensions, camera ID → stream mapping.
- CameraActivityReducer: camera + object identity; start/update/end of one object does not remove the others; motion, tracking, recording permission and detect/enabled are independent.
- Stationary labels stay in the displayed objects but do not support activeTracking.
- Live demand = visible && enabled && online && usable stream && (motion || tracking); recording OFF does not block live.
- Stats: offline only when `camera_fps == 0 && service.uptime > 60`; malformed or unknown data does not overwrite valid availability. An ordinary HTTP error is not offline.
- Visibility threshold 50%; hidden/disabled/offline immediately removes media demand.
- End of activity: the player is kept for up to 500 ms; new activity cancels the stop.
- Output readiness is bound to the identity of the current output; READY is not the same as the first frame; an old callback or detach does not change a new output.
- Snapshot polling: idle 30000 ms, starting 200 ms, live-ready 60000 ms, returning 300 ms; hidden/disabled/offline stop it. Full interval after a load, 1 ms after a slow load, error backoff at least max(interval, 1000).
- Geometry: PWA buckets 16:9, 32:9, 8:9; picture Fit without cropping.
- Session generation rejects callbacks from the old connection after STOP/reconnect.
- Credentials/session encryption: origin isolation, corrupted ciphertext, wrong key, IV uniqueness, no plaintext.

### HTTP / WebSocket / MSE

- Initial stats use the authenticated `/api/stats`; the session Cookie stays in the header, not the URL. A redirect does not forward the authorized request to another origin.
- The snapshot path encodes the camera ID correctly; the query height is limited; the cache key changes; `X-Frigate-Offline` is parsed separately from an HTTP error. Cancellation cancels the OkHttp call.
- The activity socket sends `onConnect`, accepts camera_activity/events/motion/recordings/detect/enabled/stats, and recovers after reconnect. A late REST stats response must not overwrite fresher WS stats.
- The MSE offer contains only supported video codecs. The response MIME type and the negotiated codec are checked; audio is not offered.
- DataSource preserves binary frame order; the bounded queue never loses bytes silently; overflow/close/error unblock a read.
- The buffer/catch-up policy is verified independently of the UI. The redesign does not prove a reduction in network startup latency.

The existing `MsePlaybackHtmlTest` is a legacy regression test, not confirmation of the current UI playback. The old `activeRecording` compatibility helper is not a reliable recording signal; REC is not shown on the current tiles.

### Unreviewed alert carousel and History archive

- The Live carousel shows only alert segments (`severity == "alert"`) that are unreviewed and from the last hour (the PWA window), sorted by start time descending and without duplicates by id.
- The carousel updates from the `reviews` topic: a finished alert — 1 s, a new one or genai — 6 s, a grown list of objects — 5 s.
- Marking as viewed removes the card only after a successful `POST /api/reviews/viewed`; a request error leaves the card in place.
- The carousel is reconciled with the server every 30 s (`REVIEW_RECONCILE_MILLIS`): a card whose event became older than an hour leaves the "Last hour" strip without a new WS event; reconciliation stops together with the session.
- Playback speeds match the PWA: `0.5, 1, 2, 4, 8, 16`. The label is `${rate}x` without a fractional part for whole values ("1x", "16x").
- The speed menu is a `DropdownMenu` (its own popup window) inside the control, not a child of the video box: an in-place menu gets clipped by the frame height.
- An open speed menu is not closed by the controls auto-hide timeout (4 s) until the user selects an item or closes it.
- The archive playlist is `vod/{camera}/start/{after}/end/{before}/master.m3u8`; the source window is the hourly chunk of the anchor, so seeking within the hour does not re-request the manifest; the inpoint offset is subtracted from the seek and added back to the playhead.
- Hourly chunk boundaries are at the UTC hour, as in the PWA (`getChunkedTimeDay`): in UTC+5:30 a chunk starts at the local half-hour. The test `chunkedRangeCutsTheWindowAtTheUtcHourLikeThePwa` forces the JVM zone to `Asia/Kolkata` for the duration of the check: on a machine whose clock is UTC, rounding by the local zone would pass the same test, and the divergence from the PWA would go unnoticed.
- The end of a recording inside a chunk moves playback to the next hourly chunk (`nextChunkForPlayback`): the anchor moves to the start of the next hour and playback continues; the last chunk of the window ends playback normally, without an error.
- Rebuilding the source (zoom, retry) continues from the current playhead rather than rolling back to the anchor; an explicit user seek moves to the anchor.
- Changing the camera inside the archive keeps the time of day: the playhead belongs to the viewing session, not to the camera.
- The archive `GET /api/review` request is limited by `HISTORY_REVIEW_LIMIT = 2_000`; the body of any response is limited by `MAX_RESPONSE_BODY_CHARS = 16 MiB`; exceeding it gives an explicit error, not an OOM.
- A cancelled archive load does not turn into a panel error: `CancellationException` is propagated, and the archive state belongs to the last load.
- The timeline can be scrolled by hand during playback: following the playhead is disabled by user scrolling and restored by a tap.
- The tap on the frame is handled by the video surface itself (`HistoryTextureView.onTouchEvent` does not consume the event): a clickable Compose layer over the video took the hit and left the control buttons unresponsive.

## 3. Compose UI

`LiveCameraTileTest` checks:

1. The still is visible while starting, disappears only at videoReady, and returns on a media failure.
2. The name, motion description and object labels are present; REC is absent.
3. Disabled and offline have different messages; Retry invokes the action.
4. A real opaque TextureView with a coloured frame stays under the still until videoReady, then is visible under the name overlay and the rounded clip.

These tests receive presentation state, so they **do not prove** that Media3 actually showed a frame or that the WebSocket delivered an event. A full E2E needs a controlled Frigate test server and a path from the envelope to the semantics of a specific camera.

Live navigation (camera → Back → grid, fullscreen and tabs) and the survival of the activity socket across tab switches are checked from outside the process in UiAutomator (`LiveCameraTest`, `LiveFeedTest`): this exercises the real `ConnectedApp`, its transport and system boundaries rather than presentation state.

The app allows cleartext traffic in every build, because the home address of Frigate is usually a plain `http://` address in the local network. The main address must use https (loopback is accepted for local test servers), the session cookie and credentials go only to the origin of the active address, and TLS verification does not change.

Additional acceptance scenarios: portrait/wide, two columns from 600 dp, a long name, object overflow, enlarged font, clipping of the snapshot/TextureView and overlays. Colour must not be the only indicator of state; the Retry touch target is at least 48 dp.

## 4. Physical device acceptance

Check only with fresh PID/time, `mWakefulness=Awake`, MainActivity focused and an open screenshot. A non-empty PNG proves nothing on its own.

Matrix:

- Saved session → Connected → real snapshots.
- An expired session after a long screen-off is renewed without the login screen. An unreachable server (no network, a dropped tunnel, a proxy without its upstream) never asks for the password: the app waits for the server and reconnects by itself; only a refusal of the server (401) asks for it.
- Idle → motion/tracking → native MSE first frame: the still stays until the current output frame, with no black gap.
- Sustained playback; frame/time progress matched with transport delivery. The absence of transport logs alone does not prove a break.
- End/stationary → 500 ms grace → still; new activity during the grace keeps the player.
- Several active cameras; scrolling does not recreate a player that remains visible, and a player that leaves the threshold is released.
- Media failure → still + Retry → fresh session/output identity.
- Disabled/offline/recovery based on reliable data.
- Events/Settings: media is released and polling cancelled, but the shared activity subscription is kept at the ConnectedApp level. Background/screen off/on/pause/resume: ON_STOP closes both transports, ON_START creates a fresh generation. While stopped the app asks the server nothing (the carousel too), and the camera activity seen before the stop is not shown as current.
- The screen stays on only while a video actually plays: an open camera or a playing archive, not a grid of stills or a paused archive.
- DIM while STARTED is checked separately: it is not a STOP, and loss of events is not considered fixed by a layout change.
- Home address: on a home Wi-Fi the app uses the home address, on mobile data and on any other Wi-Fi the main one; Settings show which one is active (view mode; Edit/Save/Cancel; a changed main address asks to sign in again). The switch keeps the session (no new login), recreates the activity socket and the players, and sends the cookie only to the active origin. A dead home address falls back to the main one until the network changes. Revoking the location permission returns to the main address. Check the switch during Live, History and picture-in-picture.

Do not swap one playback path for another just to get green.

### History archive

Archive matrix:

- A tap on a carousel card opens History: position = start of event − 4 s, the timeline is built, the first HLS frame is decoded (the camera's OSD is visible, not a black rectangle).
- Sustained playback: the position advances continuously, no repeated BUFFERING; the absence of transport logs alone does not prove a break.
- ±10 s: exact movement; on pause the position does not drift.
- ±10 s seek within an hourly chunk: the picture does not go dark or re-buffer, and the manifest is not re-requested; the position and the camera OSD move in sync.
- Reloading the archive (timeline zoom, retry): the playhead is kept, the video keeps playing, no rollback to the anchor.
- Scrolling the timeline with a finger during playback: the content stays in place, and auto-scroll to the playhead does not bring it back.
- Seeking on the timeline moves to the selected moment.
- Changing the camera keeps the time of day.
- Back from History returns to the Live grid and its position; leaving Live releases the archive player.
- Frame snapshot: JPEG in `Pictures/Frigate` and confirmation in the UI.
- Speed menu: opens on tap, contains all six PWA speeds, is readable on the dark background, survives the panel's hide timeout (4 s); the selected speed is actually applied (the position moves faster than real time).
- Control panel buttons respond to a tap: Pause stops the picture, mute changes the icon, ±10 s move the position, "1x" opens the speed menu, the frame snapshot and fullscreen work. Check with a tap right after the panel appears: it hides after 4 s, and `screencap` over ADB takes longer, so the tap may land on the bare picture.
- Moving to the next hourly chunk at the end of a recording: the playhead crosses the hour boundary without a gap, the camera OSD keeps running continuously, and the source change is visible in logcat; if the next chunk has no recording for the camera, playback stops normally, without an error message.
- Lifecycle ON_STOP/ON_START and screen off/on — the same requirements as for live: media is released, transports are closed, a new generation is created. The new player continues at the playhead, not at the anchor; so does the player of a renewed session, and the error of the expired one goes away.

Note on instrumentation: `adb shell uiautomator dump` between two taps swallows the next tap (an artifact of the accessibility traversal) and gives a false diagnosis of "the button does not respond". For consecutive taps, use `screencap` without dump.

The History screen is reachable only through the carousel of unreviewed alerts. If there are no such alerts in the last hour, it is unreachable from the UI — this is a limitation of the entry point, not a playback defect; verifying the archive requires either a real alert or temporary scaffolding, which must be removed before the build.

## 5. Commands and a safe device workflow

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest assembleDebug
```

Targeted JVM:

```bash
./gradlew testDebugUnitTest --tests '*LiveTilePolicyTest'
```

On a **dedicated test** device without a user session:

```bash
ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest
```

Gradle connected tests may uninstall the target APK during cleanup, affecting the app's data. Do not run this workflow on a user's device with a saved configuration without separate permission for that effect. For a non-destructive check, use install-in-place and the direct runner:

```bash
ANDROID_SERIAL=<serial> ./gradlew installDebug installDebugAndroidTest
adb -s <serial> shell am instrument -w -r \
  -e class com.frigate.android.feature.live.LiveCameraTileTest \
  com.frigate.android.test/androidx.test.runner.AndroidJUnitRunner
```

Do not clear app data or logcat, uninstall, or change device settings without permission. With several targets, always specify the serial. Do not print credentials or media payloads; do not keep test secrets in the repository.

```bash
adb -s <serial> shell am start -n com.frigate.android/.MainActivity
adb -s <serial> shell dumpsys power
adb -s <serial> shell dumpsys window
adb -s <serial> exec-out screencap -p > "$CLAUDE_JOB_DIR/tmp/live-runtime.png"
PID=$(adb -s <serial> shell pidof com.frigate.android | tr -d '\r')
adb -s <serial> logcat -d -v threadtime --pid="$PID" \
  | grep -E 'FrigateNativeMSE|CameraActivity|FATAL EXCEPTION'
```

CI gates: JVM and transport green, Compose UI green on API 31+, debug build; the physical native MSE acceptance is separate. An unimplemented or blocked layer is marked explicitly, not credited through a neighbouring green.

## 6. Sources

- https://github.com/junit-team/junit-framework
- https://github.com/android/android-test
- https://developer.android.com/develop/ui/compose/testing
- https://github.com/square/okhttp/tree/master/mockwebserver
- https://github.com/blakeblackshear/frigate
