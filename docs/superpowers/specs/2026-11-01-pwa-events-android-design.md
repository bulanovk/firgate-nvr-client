**English** | [Русский](2026-11-01-pwa-events-android-design.ru.md)

# Events: syncing the Android app with the PWA

Date: 2026-11-01. Visual basis: **mockup rev4 explicitly accepted by the user** ("принимаю" — "I accept").

- [Accepted interactive mockup](../../design/pwa-events-tab.html)
- [Implementation plan](../plans/2026-11-01-pwa-events-android.md)
- Behaviour reference: Frigate PWA checkout `dev`, `v0.18.2-356-gff2cd6a41`, commit `ff2cd6a4179a4bf83a99ee0156939d3cc016d8af`.
- Checkout used during the study: `<upstream-checkout>/frigate/`. The version of the PWA deployed for the user is not established; no version match is claimed.

## Goal and scope

Replace the current text list from `/api/events` with a full Review/Events section. This is one agreed scope: **Alerts, Detections, Motion, navigation to History, filters, swipes, multi-select, Export/Delete, and the right-hand scale in the wide layout**. The order of technical tasks is not a deferral of features to a "second phase".

Android 12+, minSdk 31, compileSdk/targetSdk 35. Kotlin 2.2.10, AGP 9.4.1, Java 21, Compose BOM 2024.12.01, Material 3; the existing Gson, OkHttp and Media3. No new libraries, no WebView, no Hilt, Retrofit, Room, DataStore or Kotlin Serialization. JVM — JUnit 5, instrumentation — JUnit 4.

Desktop hover, keyboard commands and mouse-wheel zoom are not ported. Touch pinch is ported. No Wi-Fi routing and no new archive-playback system are added. The accepted Live/History design is not changed.

## Screen and visual hierarchy

- A dark scheme consistent with Live/History. No custom large "Recent events" heading and no old text cards.
- Three types: Alerts / Detections with real counters; Motion without an invented number. The numbers 206/3 are demo data only, not app constants.
- In narrow layouts there is no right-hand scale; in wide layouts there is one. The Android technical decision: wide from `600.dp`, within the accepted wide composition. The grid adapts to the available width without horizontal scrolling of the whole Android screen.
- Card: an authenticated preview, time/duration, severity colour, object/audio icons, ongoing, reviewed, extra metadata only when present. The camera-name plate is kept as an agreed difference from the PWA.
- Cameras and "Show Reviewed" remain separate header actions; the narrow gear opens date/common-filter settings, not a toggle of reviewed. In wide layouts the date and common filters are available in the header.
- Review filters: cameras, Last 24 Hours or a calendar day, labels, zones, enabling another severity, Show Reviewed. Motion: cameras, date, Motion Only; the common review filters and Show Reviewed do not apply to Motion.
- The "All labels" and "All zones" rows are always visible (as in `ReviewFilterGroup.tsx`), even when the loaded records have no zones. Enabling clears the selection; disabling does nothing. The last checked label or zone cannot be cleared. Label and zone lists are taken only from the loaded records.
- Explicit loading / empty / filtered-empty / retry states; a refresh error does not erase cards already loaded. Dialogs and popups use dark colours, not the default light theme.
- Actions are available through semantics/TalkBack; a swipe is not the only way to mark as viewed or start a preview. Colour is not the only carrier of severity or selection.

## Interactions

1. Tapping a review card opens the **existing History**, with the camera of the tapped card and the position `startTime - 4 s`. The viewed mark is sent immediately on opening, not after the first video frame. A failed mark does not block the archive and is not presented as success.
2. Back returns to the originating Events tab with the previous filters, grid position and selected type. A historical day opens the corresponding History range rather than being clamped to today's window. Tapping the same card again from Events repositions to its start; the existing Live resume behaviour is not broken.
3. Swipe left — viewed only for a completed unreviewed review; swipe right — inline preview, **not History**. Long press — selection. Vertical scroll, tap, swipe and long press must not fire simultaneously. A selection is outlined in the severity colour; no invented round checkboxes are placed over previews.
4. When items are selected, the header is replaced by `N selected`, Unselect, Export, reviewed/unreviewed, and admin Delete. Operations use a snapshot of the selected ids. A repeated tap on a send action does not duplicate the request. Mark all includes only completed, eligible reviews.
5. Viewed/deleted state is reflected after a successful server response; on failure, an error and a retry are shown. The list, the summary and the Live carousel are updated. The reverse mark reviewed=false is supported.
6. Export: a single review and batches of up to 50, real server export jobs, status and retrieval of the finished file. Export is not equal to a toast or a local change to the list. Delete is available only to admins, with a confirmation and a warning about deleting linked recordings. Real destructive checks on the user's data require separate permission.
7. The WebSocket `reviews` topic is used as an invalidation signal, not as a full journal. New reviews do not disrupt the current scroll position; instead a "load new events" action appears. After reconnect/return — REST reconciliation; new filters cannot be overwritten by an old response.
8. The wide right-hand scale and the range overview use the same dataset/time window as the grid; tapping a segment leads to its card, pinch changes the scale. On narrow layouts the scale is absent, including for Motion; Motion's time controls remain in its own panel.

## Motion and inline preview

Motion — synchronised, accelerated previews of cameras on a shared wall-clock timeline with play/pause/seek and speeds **4, 8, 12, 16**. A separate per-camera menu opens that camera's Motion previews over a range. Selecting a recording leads to History at the chosen time, without a fabricated review id and without a viewed mark.

A preview is not a live MSE stream and not a full HLS player per card. We use the server's real preview resources and native Media3. Clocks of several cameras are synchronised by wall-clock time; a recording gap is not turned into a frame from another camera or a frozen "live" preview. Hidden tiles do not hold decoders; leaving Events and Activity STOP release media and stop polling.

Motion Only means **motion without object review**: 30-second intervals without positive motion are skipped, as are intervals intersecting an alert/detection or a review with detections/objects; ongoing ends at now. The normal shared-timeline step is 0.5 s. Per-camera clips are always built from motion-only ranges: bucket 15 s, duplicate starts merged by MAX motion, object reviews of that camera excluded, only adjacent intervals merged with a tolerance of 0.001 s. This is not just a button highlight or a `motion > 0` filter.

The spatial functions of Motion previews rely on the **16×16 `motion_heatmap` records**, weighted by the duration of the record's intersection with the window (the denominator includes records without a heatmap). It drives dimming of inactive cells, the area filter and crop. The temporal scalar `motion` cannot reconstruct spatial zones. When no heatmap exists, spatial actions are unavailable with an explanation; we do not generate yellow rectangles. An area selection is applied only on Apply, resets on camera change, and matches when any selected cell has intensity > 0; crop by bounding rectangle, enabled by default; dim 82% by default.

The limitations of the accepted HTML do not become Android limitations: the frames are synthetic, the preview is simulated, Export/Delete are local, Motion Only does not perform a real skip, and there is no heatmap. The plan implements and verifies these features on real data; the mockup proves the agreed look and transitions, but not the transport's working behaviour.

## Data, state and security

- Review list + `/api/review/summary`, in the timezone of the selected calendar. For Last 24 Hours — `last24Hours`; for a day — the matching `YYYY-MM-DD` entry. Without Show Reviewed the number is `max(total - reviewed, 0)`; with it, total. Counters are not derived from the number of loaded cards.
- All loading limits are explicit. A day must not be silently truncated at 2000 cards while showing the whole-day summary as if the list were complete. The plan provides a verifiable bounded load over time windows and an explicit message when completeness cannot be reached.
- The Events session belongs to ConnectedApp and survives History/rotation; media belongs to the visible screen. One existing activity socket, with fan-out invalidation for Live and Events; no second socket.
- No global Loading/LoginRequired because of an ordinary Events error. A 401 from any new transport leads to the existing SessionRenewal. CancellationException is not turned into a UI error. Changing profile/cookie isolates data and cancels old requests.
- Cookies only in the Cookie header; URLs relative to ServerProfile; no credentials in URLs, no trust-all TLS, and no cookie leak through redirects. Media paths are validated and constrained to the origin/base path. JSON responses are limited by the existing 16 MiB; preview/download are streamed, without loading whole videos into memory.
- No commits without an explicit request. On the device only `adb -s <serial> install -r` + `am instrument`, not connectedAndroidTest; no uninstall, no clearing of data or logcat, and no change of the system rotation.

## Proof of implementation

Each behavioural task: RED for the right reason → minimal GREEN → regression suite. Before completion: full JVM suite, debug APK, narrow and wide instrumentation, and a physical-device smoke test of real previews/History/rotation/lifecycle/session renewal. Compilation, HTML and unit tests do not confirm native playback.

When implementing, compare the interface with the accepted rev4, rather than drawing a new interpretation of the PWA. Unverified capabilities and missing test conditions are recorded explicitly. This document fixes the accepted scope and the technical decisions of the plan; by itself it does not claim that Android is already implemented or verified.
