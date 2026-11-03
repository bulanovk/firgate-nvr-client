**English** | [Русский](live-navigation-regressions.ru.md)

# Live: tap and statuses when switching tabs

2026-10-29. Fix for two regressions after the redesign.

## Causes and changes

1. When the old CameraCard was replaced by LiveCameraTile, the camera-open handler disappeared. LiveCameraScreen regained the selected camera, explicit live demand independent of motion, the toolbar Back and the system Back. The regular dashboard keeps the activity-driven policy; disabled/offline cameras are not bypassed. The snapshot cache and grid scroll state stay with the same screen owner across detail/back.
2. CameraActivityWebSocket and the activity map lived inside the conditionally mounted Live screen. Events removed them together with the media; returning started from empty statuses and depended on a new snapshot. The subscription was moved to CameraActivitySession at the ConnectedApp level, above the tab switcher. It keeps receiving updates in Events/Settings; media and polling are still released when leaving Live. ON_STOP or leaving the connected session stops the activity socket.

## Regression tests

Both defects are covered by UiAutomator tests that run the installed app against a mock Frigate (`blackbox-tests`):

- `LiveCameraTest.cameraOpensAndBackReturnsToTheGrid`: a tap on the camera opens the detail, Back returns to the grid.
- `LiveFeedTest.activitySocketSurvivesTabSwitchesAndDeliversUpdatesWhileHidden`: an object update that arrives while Events is open is visible after returning to Live, the existing person stays, the activity connection count is 1.

A debug-only cleartext allowlist for localhost/127.0.0.1 serves the local test servers. TLS verification was not disabled; the release manifest was not changed.

## Fresh check

- `testDebugUnitTest assembleDebug`, Java 21: BUILD SUCCESSFUL.
- Installation only over the app: installDebug/installDebugAndroidTest. Connected cleanup, uninstall, clear-data and clearing logcat were not used.
- Real app: Awake, MainActivity focused, fresh PID 18031. The saved session loaded without login/setup.
- Tap on the first camera: detail opened; the screenshot with decoded video was checked visually; fresh Media3 firstFrame and READY at 11:47:08.249, after BUFFERING at 11:47:07.115.
- Back returned to the grid; the release of the previous player was logged at 11:47:58.025.
- Events was actually opened (screenshot checked), the media release was logged; returning to Live showed snapshots and a motion dot on street. Delivery of an object update while in Events is deterministically checked by the transport/UI regression test, not inferred from a single screenshot.

This does not close the separate DIM problem and is not a long sustained-playback acceptance for all cameras. During detail→grid in the current PID there was also a BUFFERING transition; the absence of a rebuffer is not claimed.
