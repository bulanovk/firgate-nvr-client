**[English](2026-10-28-native-go2rtc-mse-design.md)** | Русский

# Native go2rtc MSE prototype design

Date: 2026-10-28

## Intent

Replace WebView MSE playback for the first native playback prototype. A person taps a camera in the Live list, opens that camera's actual go2rtc MSE video stream, then returns to the list with Back. Only the selected camera is decoded. The user has confirmed the configured stream has no audio.

## Goals

- Keep Live-list previews as native-loaded Frigate `latest.webp` snapshots; do not open playback WebSockets for list cards.
- On tap, show a dedicated selected-camera playback screen using Media3, without WebView.
- Connect through the existing Frigate endpoint `/live/mse/api/ws?src=...`; Frigate's nginx proxies it to go2rtc `/api/ws`.
- Negotiate video-only H.264/H.265 candidates supported by the Android decoder. Do not offer audio codec candidates. go2rtc chooses from codecs present in the source; it does not transcode an unsupported source codec just because a client offered another codec.
- On Back, close the WebSocket, cancel blocking reads, release Media3/player surfaces, and return to the camera list. Reopening makes a fresh connection.
- Report codec, transport, or decoder errors explicitly. A still image is never accepted as proof that video playback works.

## Proposed implementation

Use Media3 ExoPlayer 1.9.0 with a custom OkHttp-backed `DataSource` as a pull-to-push bridge, aligned across ExoPlayer and UI artifacts in the version catalog. Media3 1.10.1 requires compileSdk 36, so 1.9.0 preserves the app's compileSdk 35 project constraint. The app's existing minSdk 31 remains unchanged; codec capability is determined on the device at runtime. The DataSource starts the WebSocket, sends only supported video codec candidates (H.264/H.265), waits for the MIME response, then exposes ordered binary WebSocket messages as a bounded byte stream. Text errors become typed `IOException`s. Closing the DataSource closes the socket and unblocks waiting reads.

First test `ProgressiveMediaSource` with an `ExtractorsFactory` that returns exactly one `FragmentedMp4Extractor`, so extractor sniffing does not wait for more bytes from the endless live stream. Follow the PWA's live-buffer policy as closely as Media3 permits: retain up to the latest 15 seconds of media, treat more than 10 seconds behind the buffered live edge as stalled, and allow playback-rate catch-up up to 2x. Measure actual startup time and live latency rather than promising a latency SLA in this feasibility phase.

The PWA uses a 2 MiB staging array while `SourceBuffer` is busy, but that is not a strict cap on all incoming data. Use 2 MiB as the native adapter's bounded staging queue and fail explicitly rather than dropping or corrupting MP4 bytes if it is exceeded. A socket failure ends this prototype playback session with an actionable error; retry is performed by reopening the camera, not an automatic reconnect loop.

If go2rtc fMP4 init/media segments cannot be consumed correctly or the live buffer cannot be kept bounded, stop and reassess this adapter before attempting a custom MediaSource or direct `MediaCodec` implementation. This is a feasibility prototype, not a commitment to ship this path. Use `PlayerView`/Media3 video output inside a Compose detail screen. Preserve the existing `LiveStreamEndpoint` URL construction and OkHttp transport patterns. Snapshot previews are fetched as authenticated bytes using the current server origin and session cookie, then decoded to a bitmap in the Android UI layer. This keeps the HTTP boundary testable on the JVM without requiring Android's native BitmapFactory runtime. The loader must not log the cookie.

## UI and lifecycle

- The Live camera list remains visible as snapshot previews and is not a player surface.
- Tapping a card opens one camera detail/playback screen; the bottom navigation is hidden while viewing it.
- Back returns to the same list and stops playback immediately.
- Show connecting, playing, and error states; no audio controls are added.

## Verification

1. Add focused JVM tests first for video-only codec offers, MIME negotiation, binary-frame ordering, bounded queue/backpressure, server error, socket close, and cancellation. Record a RED run before production changes.
2. Add endpoint/transport integration coverage using a fake WebSocket peer that sends the go2rtc text MIME response followed by init and media bytes.
3. Run the full `testDebugUnitTest` suite and `assembleDebug` using the project's Java 21 commands.
4. On a physical Android device and the configured Frigate/go2rtc server, tap one camera and verify the first decoded frame, sustained playback, H.264/H.265 negotiation as available, Back closes the connection, and reopening starts a fresh session. Use fresh PID-filtered logs and screenshot. Do not claim runtime success from compilation or unit tests alone.

## Existing code to reuse or replace

- `app/src/main/java/com/frigate/android/core/server/LiveStreamEndpoint.kt`: existing MSE WebSocket URL and camera escaping.
- `app/src/main/java/com/frigate/android/feature/live/CameraActivityWebSocket.kt`: OkHttp WebSocket lifecycle conventions.
- `app/src/main/java/com/frigate/android/core/server/FrigateClient.kt`: server-relative URLs and session-cookie conventions; extend or reuse its HTTP boundary for authenticated snapshot bytes.
- `app/src/main/java/com/frigate/android/MainActivity.kt`: Live cards currently instantiate `MseLivePlayer`; replace automatic WebView playback with native snapshot cards and selected-camera detail state.
- Current `MseLivePlayer.kt` is reference-only for the browser MSE handshake and snapshot endpoint; remove it from the UI path only after the native flow is integrated and tested.

## Non-goals

No general-purpose MSE library, custom demuxer, direct `MediaCodec` implementation, audio playback, multi-camera simultaneous decoding, change to the Frigate/go2rtc server, WebRTC path, or declaration of production readiness based on a successful build alone.
