**English** | [Русский](live-eager-cards-design.ru.md)

# Live: all camera cards at once (option 1)

Status: implemented (2026-11-02). Owner's decision: option 1 — replace the lazy grid with a scrollable column, so that all camera cards exist when the Live section opens.

What was done:
- `LiveCameraScreen.kt`: `LazyVerticalGrid` replaced by a `Column` with `verticalScroll`; rows of two cameras when `expanded`; an odd last camera keeps the column width.
- Visibility is computed from `ScrollState` and card bounds in content coordinates (`onGloballyPositioned`); while a camera is open, there are no visible cards in the grid.
- `LiveTilePolicy.kt`: `liveSnapshotInterval(..., attempted)` — a hidden card that has not attempted a load gets one still, then polling is off.
- `MainActivity.kt`: the Live scroll state is a `ScrollState` instead of `LazyGridState`; the Events grid was not changed.
- Checks: RED `hiddenCameraLoadsOneStillBeforeItIsEverVisible` (JVM, failed on an assertion); on the device the same behaviour is checked from outside the process by the UiAutomator test `LiveFeedTest.everyCameraStillIsRequestedBeforeAnyScroll` (the still of the last of five cameras is requested without a single scroll); full unit suite.
- Not verified: memory and the number of concurrent requests with more cameras; scrolling down to the lower cards on the device.

Basis: the PWA (`LiveDashboardView.tsx`, `CameraImage.tsx`) mounts all cards at once; the deferral of snapshots there comes from the browser (`loading="lazy"`), and visibility (`IntersectionObserver`, threshold 0.5) controls only the streams. Compared against a checkout of the PWA; its version has not been matched to the deployed one.

## Problem

- `LiveCameraScreen.kt` (`LazyVerticalGrid`) composes only visible items. A card off-screen does not exist, and its `LaunchedEffect` with the first snapshot never runs.
- `liveSnapshotInterval` (`LiveTilePolicy.kt`) returns `-1` when `visible = false`: even a composed card off-screen never gets a snapshot.

So replacing the container alone does not solve the task: both changes are needed.

## Design decision

1. **Container.** `LazyVerticalGrid` → `Column(Modifier.verticalScroll(scrollState))`. Cameras are laid out in rows: when `expanded` (width ≥ 600 dp) two per row via `Row` with `weight(1f)`, otherwise one per row. The review carousel header stays the first element.
2. **Scroll state.** `LazyGridState` → `ScrollState`. The state hoisted in `MainActivity` (`val grid = rememberLazyGridState()`, around line 330) changes type; the separate Events grid is untouched. Keeping the position when going to the archive is preserved, because the state stays above the screen branches.
3. **Visibility for the player.** `visibleCameraIds` (`CameraVisibility.kt`) takes bounds in content coordinates. Each card reports its bounds via `onGloballyPositioned`; viewport = `[scrollState.value, scrollState.value + window height]`. The 50 % threshold and `visibleCameraIds` are kept; only the data source changes. The video stream still starts only for visible cameras (the policy does not change).
4. **First snapshot for hidden cards.** A new rule in `liveSnapshotInterval`: a card without a snapshot that is not visible gets one load (interval 0, once); after it, polling is off while the camera is not visible. For visible and disabled/offline cameras the behaviour is unchanged. Requires a `hasSnapshot` parameter.
5. **Card height.** Inside `verticalScroll` the height is unbounded. In `LiveCameraTile.kt` (around line 107) the `maxWidth / maxHeight` calculation gives 0 with an infinite height. The card height is set by `tileAspect` from `detectWidth/detectHeight` through `aspectRatio`, not by the parent. This must be checked by a test.

## Risks

- **Concurrent requests.** OkHttp keeps 5 requests per host by default, so snapshots do not all go at once: this is a natural limit. To be checked on the device.
- **Memory.** Each snapshot is decoded up to 720 px in height (~3.7 MB ARGB per 16:9 frame). With 10 cameras that is about 37 MB; with more it becomes noticeable. The number of cameras needs measuring on the device; a possible option is `inSampleSize` based on the card size.
- **Traffic and server load.** When the section opens, N requests for `latest.webp`. Periodic polling remains only for visible cameras, so the constant load does not grow.

## Work plan (TDD per CLAUDE.md)

1. RED, JVM: `liveSnapshotInterval` for a hidden card without a snapshot returns 0 (once), with a snapshot — `-1`.
2. RED, JVM: visible ids from `scrollOffset` and content bounds (a pure function, no Android imports).
3. GREEN: the interval rule and the visibility function.
4. RED on the device (UiAutomator): the still of the last camera is requested without scrolling.
5. GREEN: `Column` + `verticalScroll`, rows by width, `ScrollState` in `MainActivity`.
6. Full unit suite (`testDebugUnitTest`), `assembleDebug`.
7. Device (`adb install -r`, without `connectedAndroidTest`): snapshots of all cameras before scrolling; video does not start for hidden cameras; screen rotation (`LiveCameraTest.theCameraStaysOpenAfterTheRotation`); measurement of memory and the number of concurrent requests.

## Open questions

- How many cameras does the owner have? Memory and the need for `inSampleSize` depend on this.
- Is virtualization needed for a large number of cameras? For now, it is assumed not.
