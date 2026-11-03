**[English](2026-10-28-native-go2rtc-mse.md)** | Русский

# Native go2rtc MSE Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace automatic WebView video playback with a native video-only go2rtc MSE prototype that opens the selected camera on tap and stops on Back.

**Architecture:** OkHttp negotiates the go2rtc MSE WebSocket and supplies ordered fMP4 bytes through a bounded custom Media3 `DataSource`. Media3 `ProgressiveMediaSource` with one `FragmentedMp4Extractor` is the first feasibility path. The Live list loads authenticated `latest.webp` snapshots natively; the detail screen owns exactly one player and socket.

**Tech Stack:** Kotlin, Compose, OkHttp 4.12.0, AndroidX Media3 1.9.0 (1.10.1 requires compileSdk 36 while this project remains at compileSdk 35), JUnit 5, MockWebServer 4.12.0 for transport integration tests.

**Spec:** `docs/superpowers/specs/2026-10-28-native-go2rtc-mse-design.md`

## Global Constraints

- Keep `minSdk 31`; do not add WebView playback, audio, direct `MediaCodec`, or custom demuxing.
- Offer only H.264/H.265 video codec strings that the Android decoder supports; never offer audio codecs.
- Bound the MSE staging queue to 2 MiB and fail rather than dropping or corrupting MP4 bytes.
- Mirror the PWA as closely as Media3 permits: about 15 seconds retained, over 10 seconds behind live is stalled, catch-up speed is capped at 2x.
- Back closes the WebSocket, unblocks reads, and releases ExoPlayer; reopen creates a new stream session.
- Keep session cookies server-scoped and out of logs; keep TLS verification enabled.
- Run JVM tests and debug APK build with Java 21. Device playback requires fresh Android runtime evidence.
- Do not create commits unless explicitly requested.

## Review Focus

- Unsupported H.264/H.265 decoder or a go2rtc codec mismatch must show a playback error; test negotiation/response mismatch in Task 1 and Task 2.
- Text error/MIME control messages must not enter the fMP4 byte stream; test in Task 1 and Task 2.
- Binary WebSocket frame boundaries and order must be preserved when read by Media3; test in Task 1 and Task 2.
- More than 2 MiB queued must fail deterministically without silent byte loss; test in Task 1.
- Back while connecting or playing must close transport and release the player; test in Task 2 and verify in Task 4.

---

### Task 1: MSE negotiation and bounded byte queue

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/live/Go2RtcMseProtocol.kt`
- Create: `app/src/main/java/com/frigate/android/feature/live/MseByteQueue.kt`
- Test: `app/src/test/java/com/frigate/android/feature/live/Go2RtcMseProtocolTest.kt`
- Test: `app/src/test/java/com/frigate/android/feature/live/MseByteQueueTest.kt`

**Interfaces:**
- Produce `Go2RtcMseProtocol.videoCodecOffer(supportedMimeTypes: Set<String>): String`, containing only supported video candidates and no audio candidates.
- Produce `Go2RtcMseProtocol.parseControlMessage(text: String): MseControlMessage`, with typed `Negotiated(mimeType)`, `ServerError(message)`, and `UnexpectedMessage` results.
- Produce `MseByteQueue(capacityBytes: Int = 2 * 1024 * 1024)` with `offer(bytes: ByteArray): Boolean`, blocking `read(target: ByteArray, offset: Int, length: Int): Int`, and `close(cause: IOException? = null)`; close must wake blocked readers.

- [ ] **Step 1: Write failing protocol tests** named `offersOnlySupportedVideoCandidates`, `returnsEmptyOfferWhenNoVideoDecoderIsSupported`, `parsesNegotiatedVideoOnlyMime`, `parsesGo2RtcCodecMismatch`, and `rejectsUnexpectedControlMessage`; assert audio codec names are absent from every offer.
- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests com.frigate.android.feature.live.Go2RtcMseProtocolTest`** and confirm the new API is missing.
- [ ] **Step 3: Implement `Go2RtcMseProtocol`** as a pure Kotlin parser/offer builder; use go2rtc-supported candidate strings and omit all audio strings.
- [ ] **Step 4: Run the protocol test** and confirm all cases pass.
- [ ] **Step 5: Write failing queue tests** named `readsBinaryFramesInOrderAcrossPartialReads`, `closeUnblocksWaitingReader`, `propagatesCloseCause`, `acceptsExactlyCapacityBytes`, and `rejectsOverflowWithoutDroppingQueuedBytes`.
- [ ] **Step 6: Run `./gradlew testDebugUnitTest --tests com.frigate.android.feature.live.MseByteQueueTest`** and confirm the queue API is missing.
- [ ] **Step 7: Implement `MseByteQueue`** with a lock/condition or equivalent bounded structure; never discard bytes.
- [ ] **Step 8: Run the queue test** and confirm all cases pass.

### Task 2: OkHttp WebSocket Media3 DataSource

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/frigate/android/feature/live/Go2RtcMseDataSource.kt`
- Test: `app/src/test/java/com/frigate/android/feature/live/Go2RtcMseDataSourceTest.kt`
- Add test dependency: OkHttp MockWebServer 4.12.0

**Interfaces:**
- Produce `Go2RtcMseDataSourceFactory(webSocketUrl: String, cookie: String, codecOffer: String, client: OkHttpClient): DataSource.Factory`.
- `Go2RtcMseDataSource.open(DataSpec): Long` opens one WebSocket, sends `{"type":"mse","value":"<video codec offer>"}`, waits for negotiated video MIME, and returns `C.LENGTH_UNSET`.
- `read(...)` returns bytes from `MseByteQueue`; `close()` closes the socket and unblocks pending reads. Expose the negotiated MIME to the player through a typed callback or immutable session result, not logs.

- [ ] **Step 1: Add Media3 1.9.0 and MockWebServer 4.12.0 aliases** to the version catalog and module dependencies; add no runtime dependency besides Media3.
- [ ] **Step 2: Write failing MockWebServer WebSocket tests** named `sendsVideoOnlyOfferWithSessionCookie`, `waitsForMimeBeforeReturningSourceBytes`, `preservesBinaryFrameOrder`, `surfacesServerCodecError`, `socketCloseUnblocksDataSourceRead`, `closeCancelsNegotiationWait`, and `queueOverflowFailsWithoutDroppingBytes`; assert the first response MIME is exposed to the player and never appears in `read()` output.
- [ ] **Step 3: Run the targeted test** and record the expected RED caused by the missing DataSource.
- [ ] **Step 4: Implement `Go2RtcMseDataSource`** as a blocking Media3 pull adapter over OkHttp listener callbacks; parse text controls separately and enqueue only binary payloads.
- [ ] **Step 5: Run the targeted DataSource tests** and confirm all pass.
- [ ] **Step 6: Run the full JVM suite** to catch dependency or existing transport regressions.

### Task 3: Native Media3 player and selected-camera navigation

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/live/NativeMsePlayer.kt`
- Create: `app/src/main/java/com/frigate/android/feature/live/CameraSnapshotLoader.kt`
- Modify: `app/src/main/java/com/frigate/android/MainActivity.kt`
- Test: `app/src/test/java/com/frigate/android/feature/live/CameraSnapshotLoaderTest.kt`
- Test: `app/src/test/java/com/frigate/android/feature/live/MseLiveBufferPolicyTest.kt`

**Interfaces:**
- `NativeMsePlayer(context: Context, dataSourceFactory: DataSource.Factory, onStateChanged: (NativeMsePlaybackState) -> Unit)` owns one ExoPlayer and exposes it to Compose `PlayerView`; `prepareAndPlay()` prepares the progressive source, and idempotent `release()` closes the active source/socket. `NativeMsePlaybackState` has `Connecting`, `Buffering`, `Playing`, and `Failed(message)` states.
- `MseLiveBufferPolicy` exposes `retainedWindowMs = 15_000`, `stalledLagMs = 10_000`, and `playbackRateForLag(lagMs: Long): Float`, capped at `2.0f`.
- Build a `ProgressiveMediaSource` with `ExtractorsFactory { arrayOf(FragmentedMp4Extractor()) }`; configure a 15,000 ms maximum buffered duration, 10-second stalled-behind-live threshold, and catch-up playback rate capped at 2.0.
- `CameraSnapshotLoader.loadBytes(profile: ServerProfile, cameraId: String, cookie: String): ByteArray` is a `suspend` function that requests the camera's `latest.webp` using the current origin and session cookie; Compose decodes the returned bytes using Android's `BitmapFactory`.
- Add a selected-camera detail state to `FrigateApp`/`ConnectedApp`; camera tap selects it, `BackHandler` clears it, and only the selected detail screen creates the native player.

- [ ] **Step 1: Write failing snapshot loader tests** named `encodesCameraIdInLatestWebpPathAndAddsSessionCookie` and `rejectsNonSuccessResponse` using MockWebServer; verify the returned bytes include the WebP response payload. Decode-to-bitmap behavior is covered by Android runtime acceptance because `BitmapFactory` is unavailable in JVM tests.
- [ ] **Step 2: Run the targeted snapshot tests** and confirm they fail before the loader exists.
- [ ] **Step 3: Implement `CameraSnapshotLoader.load`** as a suspend function using the existing server URL and cookie conventions; do not log URL credentials or cookies.
- [ ] **Step 4: Run snapshot tests** and confirm pass.
- [ ] **Step 5: Write `MseLiveBufferPolicyTest` cases** named `capsRetainedMediaAtFifteenSeconds`, `marksLagOverTenSecondsStalled`, and `capsCatchUpRateAtTwoX`.
- [ ] **Step 6: Implement the pure `MseLiveBufferPolicy` and `NativeMsePlayer`**; initially use the single `FragmentedMp4Extractor` progressive source. Keep startup, playing, and failure state observable to Compose.
- [ ] **Step 7: Replace `CameraCard`'s automatic `MseLivePlayer` use with the native snapshot view and click callback; add selected-camera detail screen and Back cleanup.** Preserve the existing camera activity WebSocket independently.
- [ ] **Step 8: Run targeted tests and `./gradlew testDebugUnitTest assembleDebug`**; confirm tests and APK build pass.

### Task 4: Device acceptance and feasibility decision

**Files:**
- Modify only implementation/tests/docs if device findings require a fix; no unrelated refactor.

- [ ] **Step 1: Install the fresh debug APK on one explicit ADB serial** and open Live; confirm cards show snapshots and do not open MSE playback sockets.
- [ ] **Step 2: Tap one camera**; verify WebSocket codec negotiation, MIME response, first decoded video frame, sustained playback, bounded 15-second window, and no audio track.
- [ ] **Step 3: Press Back during both startup and playback**; verify socket closes, player releases, and camera list returns. Reopen and verify a new socket/session.
- [ ] **Step 4: Capture fresh PID-filtered logs and screenshot**; report device/Android version, selected codec, first-frame time, observed lag, and any failure. Do not clear logcat.
- [ ] **Step 5: If the progressive fMP4 path fails**, record the exact go2rtc MIME response, Extractor/player error, and runtime evidence; stop before implementing custom demux or direct `MediaCodec` and return for a design decision.

## Execution Notes

- Execute tasks in order; Task 2 consumes Task 1 interfaces, and Task 3 consumes Task 2.
- Use TDD for behavior changes: write one focused test, run and record RED, implement minimal code, run GREEN, then the full suite/build.
- No commits unless explicitly requested.
