**English** | [Русский](live-cold-start-investigation.ru.md)

# Live: activity recovery after a restart

2026-10-29. Status: the cause of the user-facing failure is not established; no fix is claimed.

Diagnostics were added to `CameraActivityWebSocket`: sending onConnect, receiving camera_activity, and aggregated sizes of the state (motion/tracking/objects). Raw envelopes, camera IDs, URLs, credentials, cookies and media payloads are not logged. The final version writes state only when the reducer state changes, not on every message.

## Physical checks

Three consecutive launches after the process ended, installed over the existing app, without uninstall/clear-data and without clearing logcat:

| PID | Socket opened | camera_activity received | Result |
|---|---|---|---|
| 22195 | 11:53:42.737 | 11:53:42.842 | 5 cameras, motion/tracking/objects restored |
| 23561 | 11:58:22.740 | 11:58:22.792 | 5 cameras, motion/tracking/objects restored |
| 24077 | 11:58:50.061 | 11:58:50.107 | 5 cameras, motion and stationary objects restored; tracking updated afterwards |

Each process recorded a native MSE firstFrame. The screenshots of all three launches were checked: real cameras and indicators are visible. In the last launch, Awake and MainActivity focus were confirmed separately. The saved session works without signing in again.

This does not prove the absence of an intermittent failure. Phone reboot, network loss during onConnect, and a repeat of the original user episode were not tested. The 46–105 ms delays were measured from opening the socket to receiving the envelope, not from app launch to the UI being displayed.

## Verification of the final diagnostic build

`testDebugUnitTest assembleDebug installDebug` with Java 21: BUILD SUCCESSFUL. Installed over the app; then MainActivity was launched with LaunchState=COLD. This is a check of the diagnostics, not a RED→GREEN bug fix.

## Next step

On the next reproduction, match the current PID, onConnect, camera_activity, the aggregated reducer state and the UI. While there is no data about a failed launch, do not add persistence of stale statuses or an arbitrary retry as if the cause were established.
