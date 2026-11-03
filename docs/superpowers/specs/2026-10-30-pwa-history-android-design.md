**English** | [Русский](2026-10-30-pwa-history-android-design.ru.md)

# Native Android: Live unreviewed-alerts carousel and History from the PWA

> **Status: operative contract.** It replaces the "Viewing a recording" section of `2026-10-30-live-review-carousel-design.md`, which the user rejected on 2026-10-30. The bounded-VOD player (a limited clip, a horizontal slider, its own event-meta panel) is not implemented.
>
> Agreed with the user on 2026-10-30: the browser mockups `docs/design/live-unreviewed-carousel.html` (carousel) and `docs/design/live-pwa-history.html` (History). The mockups are the visual and behavioural contract. The Android implementation must repeat their scenarios rather than invent its own.

## Source of truth

Frigate upstream checkout: `<upstream-checkout>/frigate`. A match with the PWA version deployed for the user has not been established. The checkout was not run. If the checkout behaviour diverges from the mockup, the mockup takes priority, and the divergence is recorded in the verification report.

## Live carousel (unchanged from the accepted mockup)

- A compact horizontal strip under the Live heading, before the camera grid. Dashboard only; not the camera detail and not the recording screen.
- Up to 10 unreviewed review segments (`severity=alert`, `reviewed=0`) that started within the last hour; newest on the left. The source is `/api/review`, not `/api/events`.
- A static authenticated preview, the camera name, relative time, objects, a sign that the event is still ongoing, and a separate touch button to mark it as viewed. No autoplay previews and no auto-scrolling.
- An empty list hides the whole block. Initial loading, a list error with Retry, and an unavailable individual thumbnail are shown differently. A failed refresh does not remove the list already received.
- Simply displaying or scrolling marks nothing. An explicit mark and opening History send `POST api/reviews/viewed` with `{"ids":[id],"reviewed":true}`. A card leaves the strip only after confirmed success; on error, a retry is offered without a false success. History opens regardless of the mark's result.
- REST is the source of the list. The existing shared `/ws`, topic `reviews`, is only a revalidation signal, delivered through an independent callback before the camera-reducer deduplication; no second WebSocket. End — 1 s; new/genai — 6 s; growth of objects — 5 s. Reconnect, return, and entering Live restore the list through REST. While Live is visible, a reconciliation runs every 30 s.

## History: recording viewer

### What it is

History is an archive-viewing workspace, not a separate clip. It opens when an event is tapped (in the Live carousel or in the Events panel) and shows the recording of the selected camera at an arbitrary moment of the archive.

- The state source is `GET /api/review` for a camera and time range; this is the same review-segment contract the carousel uses, so no separate data model is needed.
- The default range on a normal entry is the last 24 hours from the moment of opening (`after = now - 86400`, `before = now`). Day boundaries apply only when a day is picked in the calendar.
- The start position is `start_time - 4` seconds of the event (the PWA's REVIEW_PADDING). If the event is outside the selected range, the position is `range_end - 60`.
- Playback is not limited to the event: going past `end_time` keeps playing through the archive until the end of the range is reached.
- Switching cameras keeps the current moment (wall-clock), not the position relative to the event.

### Layout

Portrait:
- Top bar, 48 dp: on the left Back and Live of the selected camera; in the centre the Frigate logo; on the right camera selection, panel mode, settings. Icons only.
- Video on top, `Fit` on a black backdrop, rounded corners, at most 50 % of the screen height; never cropped or stretched.
- The panel takes the remaining height.

Landscape:
- Video on the left, panel on the right: Timeline — 100 dp; Events/Detail — about 19 rem (304 dp).
- There is no auxiliary strip of second cameras.

### Panels

Switched through a bottom drawer with three options: **Timeline / Events / Detail**. The selected row gets the secondary background. Choosing an option closes the drawer. The panel mode is remembered within the viewing session.

**Timeline** — a vertical scale of the whole selected range:
- Newest at the top, earlier moments below.
- Segment step 30/15/5 s at a scale of 15 min / 5 min / 1 min; the scale is changed with the `+`/`−` buttons in the bottom-right corner of the panel.
- A row is 8 dp. Motion lines are symmetric about the axis, two 2 dp lines per row.
- Recording without motion is a thin grey line. Recording gaps are black with borders.
- Severity ranges: alert — red fade, detection — orange; reviewed ones are dimmed.
- The playback position is a red line across the full width and a central rounded tag with the time (80 dp). The tag can be dragged to seek; tapping a segment also seeks.
- The scale follows the position until the user interacts with it.

**Events** — all review items of the selected camera for the range, not only the initially unreviewed ones:
- A card with a preview, a severity dot, an object icon, the time and the relative age.
- Tapping seeks to `start - 4` and starts playback.

**Detail** — expandable groups of review items:
- A group row: time, severity, number of objects, duration. The active group is expanded by default.
- Inside — the object lifecycle (detected / entered zone / active / left zone) with exact seek by time.
- At the bottom — "Detailed-view settings": annotation offset and "Always expand active".

### Playback controls

A floating semi-transparent panel (60 % black) near the bottom edge of the video; button order:
1. sound / mute (the volume slider appears when sound is on),
2. −10 s,
3. play/pause,
4. +10 s,
5. speed (0.5 / 1 / 2 / 4 / 8 / 16),
6. frame snapshot,
7. fullscreen.

- There is no own horizontal duration slider, no elapsed/total counters, no permanent block with the event name, and no "Done/Mark" row.
- On a phone the controls hide 4 s after start; a tap on the picture shows/hides the panel but does not toggle playback.
- Keyboard: ←/→ ±10 s, ↑/↓ ±1 s, space — play/pause, `m` — mute, `f` — fullscreen.
- There is no separate scrub slider: seeking is done only through the archive timeline.

### Navigation

- Back returns to Live and restores the grid position.
- Live from History opens Live for the **selected** camera, not just the dashboard.
- Switching to another tab closes History.

### Viewing-settings menu

A bottom drawer with a single column of full-width buttons: Export, Calendar, Filter, Share moment, Motion search.

- **Calendar** changes the range to the selected day; "Last 24 hours" returns to the original.
- **Filter** — object labels (and zones, if the server provides them) and severity toggles. This is not a camera choice. The last explicitly selected label cannot be removed. There are "Apply" and "Reset" buttons.
- Export, "Share moment" and "Motion search" in this version explain their purpose and do not perform an action. This is a deliberate simplification, not a silent substitution of behaviour.

## Recording transport

- Archive playback is a separate Media3 HLS player, not the native go2rtc MSE and not a reuse of the live player.
- The URL and parameters come from the PWA contract (see `docs/design/live-pwa-history-sources.md`); the camera is the Frigate camera ID, not the go2rtc stream ID.
- The session cookie is sent only in the header of its own origin. Playlists, segments, keys and thumbnails are checked against the origin before the request; redirects are forbidden, including between identical protocols on different hosts. `allowCrossProtocolRedirects=false` is not treated as same-origin protection.
- Local, asset and content URIs from the manifest are forbidden.
- Sound is off by default.
- Errors are distinguished: no recording (404), forbidden (403), login required (401 → login screen), network/codec error. An endless spinner is forbidden; every error has a Retry.
- Switching camera, range, timeline scale and retry change the player's identity; a late callback from a previous generation does not change the UI.

## Security and compatibility

- Android 12+, Kotlin 2.2.10, Compose BOM 2024.12.01, Media3 1.9.0, OkHttp 4.12.0, Gson 2.11.0. No WebView. No new library families; `media3-exoplayer-hls` and `media3-datasource-okhttp` of version 1.9.0 are allowed.
- Credentials are forbidden inside URLs. TLS verification is not disabled. Session data does not cross the origins of different `ServerProfile`s.
- `thumb_path` is an untrusted server path: only the expected `/media/frigate/clips/review/…` is accepted and converted to a profile-relative `clips/review/…`. Absolute URLs, traversal and unknown paths fall back to a placeholder rather than a load from a foreign address.
- The session generation protects against late responses from a previous profile/cookie. The preview cache, pending writes and the player do not carry over between sessions.
- Touch targets ≥ 48 dp, content descriptions on all icon buttons, readability at a large font size, colour is not the only indicator of state.
- A carousel item must not enter camera visibility; its key differs from the camera ID.
- Do not change MSE negotiation/buffering, the motion/tracking gates, or camera availability. No Wi-Fi routing.

## Limits of proof

- Unit tests and the build do not prove playback: the first decoded frame, long-running playback and release on leaving History require a physical Android device.
- Server-side viewed writes change the user's status: perform them only for an explicitly allowed test segment; otherwise verify the mutation on a mock transport and state the runtime-check limitation explicitly.
- No commits without an explicit request. Work is done in place.
