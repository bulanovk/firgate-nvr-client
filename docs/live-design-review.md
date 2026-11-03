**English** | [Русский](live-design-review.ru.md)

# Live — design review and implementation

Date: 2026-10-29.

Follow-up fix: [camera tap and statuses when switching tabs](live-navigation-regressions.md). Detail/Back were restored; the activity subscription was moved above the tabs. New check: 6/6 device tests, a real first frame and an Events round-trip. The session blocker described below belongs to the initial run, not to the later smoke test.
Basis: [Frigate Live specification](frigate-live-protocol.md), the local source reference of the Frigate PWA (`LivePlayer`, `LiveDashboardView`). The checkout version has not been matched to the deployed server; pixel-perfect parity with the running PWA is not claimed.

Continuation: [live camera panel — review against the PWA and zoom as a player property](live-camera-panel-review.md) (a separate camera screen, zoom for live and archive).

## Review findings

The main problem of the previous screen was that service elements competed with the image, and recording permission, activity and video readiness were mixed together. The correct hierarchy: image → objects/name/motion → a compact error only when needed.

| Before | Implemented |
|---|---|
| Large FRIGATE / Live header | Compact Live toolbar |
| Fixed height 220 dp and Crop | Aspect buckets 16:9 / 32:9 / 8:9, Fit |
| REC ON/OFF, Updated, STALE over the image | No REC or service timestamps; a separate motion dot |
| Scattered object badges | A single grey chip on the left, overflow +N |
| Stop/start from the recording state | Visibility + enabled/online + motion/non-stationary tracking |
| Snapshot disappeared before the real video output | The still is hidden by the current Surface first frame + texture update |
| Immediate stop at the end of activity | 500 ms grace, cancelled by new activity |
| A single camera error | Separate disabled, offline, snapshot failure and media error + Retry |

## Layout

- One column on a phone is the PWA default, not a defect; two from 600 dp.
- Gap 8 dp; radius 8 dp compact / 16 dp expanded.
- White object icons in a grey chip at the top left; stationary objects stay visible.
- The name is always shown at the top right: a deliberate Android default that matches the PWA `alwaysShowCameraName`.
- The red dot means motion, not a recording. Tracking gives a 3 dp orange outline, not a review severity.
- Common clipping of the picture/video/overlays. Fit does not crop the edges; the picture aspect is refined from the decoded video size.
- Retry is at least 48 dp; states have text/semantics, not only colour.

Rendering: `app/src/main/java/com/frigate/android/feature/live/LiveCameraTile.kt`.
Orchestration: `LiveCameraScreen.kt`.

## Behaviour and ownership

- The snapshot cache survives recomposition and scrolling. Useful intervals: idle 30 s, startup 200 ms, ready 60 s, returning 300 ms; hidden/disabled/offline stop.
- Recording OFF does not block activity-driven live.
- The player pool keeps the visible camera/stream/session/retry identities. Cameras that leave the viewport are released; returning may create a new stream.
- `NativeMseVideo` owns the TextureView Surface; the pool owns the player. Exact Surface identity is checked in `AnalyticsListener`; READY is not first-frame readiness. A texture update is also required before the still is removed.
- ON_STOP / leaving Live releases media and the socket and cancels snapshot work. The generation gate rejects late callbacks.
- Offline is determined by stats or the snapshot header, not by an arbitrary network error.
- Native MSE, video-only, the buffer policy and secure session storage are kept. No WebRTC/WebView fallback was added.

## Recent checks

- Regression RED→GREEN: config retention/dimensions, stationary/enabled/stats, initial stats and redirect rejection, snapshot metadata/height, demand/readiness/grace/polling/aspect, session generation.
- Full JVM suite + `assembleDebug`: BUILD SUCCESSFUL (Java 21).
- `LiveCameraTileTest` on a physical Android device: **4/4 passed**, direct AndroidJUnitRunner, final run 3.741 s. An additional test draws a real red frame in an opaque TextureView: a green still covers it until videoReady, then the frame is visible under the name chip with rounded clipping. The screenshot was opened and checked. This verifies interop compositing, not Media3 decoding.
- Fixed the discovery of the existing `CameraSnapshotLoaderTest.rejectsNonSuccessResponse`: before the change the targeted run reported "No tests found"; after an explicit Unit return the test runs and passes.
- A screenshot of a real Compose tile with a **test**, not camera, image was obtained and opened: the name, motion dot, object chip, tracking border and clipping are visible. This is not proof of the MSE handoff.

### Device acceptance blocker

The baseline before the changes showed Connected and real snapshots. After an early Gradle connected instrumentation run and the subsequent installation, the app ended up on the setup screen with no saved server. The probable cause is the uninstall of the target APK during Gradle connected-test cleanup; the causality is not confirmed by a separate trace. No explicit clear-data or uninstall commands were run. The data/settings were not restored or changed automatically afterwards.

The first black screenshot was taken during the launch transition; a repeat settled screenshot with Awake, MainActivity focused and PID 6856 showed onboarding. An empty filtered MSE log proves neither a break nor a successful playback: there is no connected camera in this run.

**Not confirmed on the new screen:** the real first-frame handoff, sustained multi-camera playback, release on scroll/Events/background, offline recovery, DIM, landscape and an enlarged font. To complete acceptance, the server must be connected again through the UI and the matrix from the [testing strategy](testing-strategy.md) run. A reduction of the earlier 5–10 s startup and a fix for the loss of events during DIM are not claimed.

### Safe repeat of the check

On the user's device, do not repeat `connectedDebugAndroidTest` without permission for uninstall/cleanup. Use install-in-place and the direct runner; keep the Gradle connected workflow for a dedicated test target. Do not clear logcat.

## Decisions taken

1. Work in place, without commits/worktrees: the directory is not a Git repository. The cost: the changes have no commit history; the report and tests remain the record of the results.
2. An explicit Surface + TextureView instead of the XML PlayerView: allows ownership to be verified by the actual output. The cost: a small custom texture host and a mandatory physical compositing check.
3. The reference is the source checkout, not a screenshot of the deployed PWA. The cost: visual/version parity with the specific server is not confirmed.
4. A full connected instrumentation repeat is not run on the user's target because of the risk of deleting data; the direct runner passed. The cost: this Gradle gate remains unconfirmed.

## Independent review

One independent read-only review and one fix pass were performed.

- **F1, TextureView supposedly over all Compose overlays:** not confirmed. The physical `textureOutputStaysUnderStillAndOverlays` passed without changing the production renderer: the centre is green while the still is shown, red after videoReady, the name chip is above the texture, corners clipped. The proposed alpha/SurfaceView changes were not made. The cost of this decision: other devices and the real decoder still need acceptance.
- **F2, a repeated prepare inherits readiness:** the stated path was not confirmed by the callers. The only production caller invokes prepare after creating a new Surface and `attachOutput`, which resets readiness; the error callback also reports false. Retry creates a new player. The change was not made. The cost: if a prepare on the same binding appears, the contract must be revisited.
- **F3, an HTTP failure clears the cached offline state:** the stated user scenario is not confirmed. The offline header switches the interval to stop and cancels requests; the next failed poll without a stats override does not run. With stats online, the runtime is already the priority reliable source. The change was not made. The cost: if offline polling changes, the metadata must be preserved explicitly.
- **F4, a mode change cancels an in-flight image:** confirmed and fixed. `modeChangeDoesNotCancelInFlightSnapshot` first failed on a cancelled request, then passed. `pollCameraSnapshots` changes only the timer; the refresh stays sequential and is not cancelled on 200↔30000↔60000 ms. Hidden/disabled/offline/STOP still cancel the work. The full JVM suite and the build passed after the fix.
- **F5, the last activity before the reconnect snapshot:** kept on purpose, as in the PWA. The generation gate excludes old callbacks; a new snapshot restores the state. The cost: during a disconnection the UI temporarily shows the last known activity.
- **F6, wide recomposition on snapshot writes:** minor, deferred. The screen reads the map when building the offline key; equality prevents an unnecessary restart of the effect, but not the recomposition itself. A possible optimization is a derived offline state; the cost is extra UI work during startup polling.

No confirmed critical defects remain after the fix pass; runtime acceptance remains blocked. Deferred minor item: F6.
