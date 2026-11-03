**English** | [Русский](2026-10-30-live-review-carousel-design.ru.md)

# Live: unreviewed alerts and viewing a recording

> **SUPERSEDED.** On 2026-10-30 the user rejected the author's simplified bounded-VOD player and accepted the new History mockup based on the PWA (`docs/design/live-pwa-history.html`). The operative contract is `docs/superpowers/specs/2026-10-30-pwa-history-android-design.md`. The section "Viewing a recording: recommended bounded first stage" below is the rejected proposal — do not implement it.

## Agreed

On 2026-10-30 the user accepted the browser mockup `docs/design/live-unreviewed-carousel.html` and chose to open a recording when an event is tapped. The mockup is the visual basis; the existing native MSE Live is kept.

## Carousel

- A compact horizontal strip under the Live heading, before the camera grid. Dashboard only; not the camera detail and not the recording screen.
- Up to 10 unreviewed review segments with severity=alert that started within the last hour; newest on the left. This is `/api/review`, not `/api/events`.
- A static authenticated preview, the camera name, relative time, objects, a sign that the event is still ongoing, and a separate touch button to mark as viewed. No automatic paging and no autoplay previews.
- An empty list hides the block entirely. Initial loading, a list error with Retry, and an unavailable specific thumbnail are distinguished. A failed refresh does not remove the list already received.
- Simply displaying or scrolling marks nothing. An explicit mark and opening a recording send `POST api/reviews/viewed`, `{"ids":[id],"reviewed":true}`. Removal from the strip happens only after confirmed success; on error, show a retry without a false success. The recording screen may open regardless of the result of the mark.
- REST is the source of the list. The existing shared `/ws`, topic `reviews`, serves as a revalidation signal through an independent callback before the camera reducer deduplicates; no additional WebSocket is needed. End — 1 s; new/genai — 6 s; growth of objects — 5 s. Events are merged without deferring an earlier refresh indefinitely. Reconnect, return, or entering Live always restores the list through REST. While Live is visible, a periodic 30 s reconciliation updates the relative time/hour filter and the statuses from another client.

## Viewing a recording: recommended bounded first stage

- A separate native Media3 VOD screen with the camera/event title, Back, play/pause, seeking within the loaded interval, buffering, and a clear error/Retry. This is a recording, not a switch of the selected camera to live.
- Start at `max(0, start_time - 4)`; for a completed segment — up to `end_time + 4`, not later than the current time; for an ongoing one — up to the current time of opening. For the ongoing case there is an explicit status "Event still in progress" and an action to refresh the available recording. Do not switch to live automatically.
- Build the HLS URL from the profile: `vod/{encoded camera}/start/{start}/end/{end}/master.m3u8`, without `api/` in front of the external nginx VOD path. The camera is the review ID, not the go2rtc stream ID.
- An empty recording, segments not yet saved, 403/404, or a codec error — each a separate clear state, not an endless spinner. Retry uses a fresh end for ongoing events.
- When leaving the dashboard, Live media and snapshot polling stop; the shared activity stays. When leaving the recording or on Activity STOP, the player and network operations are released. Returning to START restores the position and play/pause state in a new player session; Back restores the dashboard and the grid position.
- Recording sound is off by default. Audio/PTZ/full archive timeline/export/fullscreen are not added by this task.

## Security and compatibility

- Android 12+, Kotlin/Compose/Media3 1.9.0; no WebView and no new library family. The current Media3 modules `exoplayer-hls` and `datasource-okhttp` of the same version are allowed.
- Cookies only in request headers for their own origin. Check the origin of every manifest/segment/key/thumbnail request before sending; redirects are forbidden, including between identical protocols on different hosts. Do not rely on `allowCrossProtocolRedirects=false` as same-origin protection.
- The thumbnail `thumb_path` is an untrusted server path: accept only the expected `/media/frigate/clips/review/…`, converting it to a profile-relative `clips/review/…`; forbid absolute URLs, traversal, and unknown paths. A missing or unsupported path gives a fallback, not a load from a foreign address.
- The session generation protects against late responses from a previous profile/cookie. The thumbnail cache, pending writes and the player do not carry over between sessions.
- Touch targets ≥ 48 dp, descriptions, readability with a large font; colour is not the only indicator of state. The strip takes part in the vertical scroll of the grid; its item key must not get into camera visibility as if it were a camera.
- Do not change MSE negotiation/buffering, the motion/tracking gates or camera availability. No Wi-Fi routing.

## Sources and limits

PWA source reference: `web/src/views/live/LiveDashboardView.tsx`, `components/card/AnimatedEventCard.tsx`, `components/player/dynamic/DynamicVideoPlayer.tsx`, `types/review.ts`; backend `frigate/api/review.py`, `frigate/api/media.py`, nginx `/vod/` and `/clips/`. The checkout is in `<upstream-checkout>/frigate`; a match with the deployed server has not been established.

The bounded VOD interval and the muted default are a proposed implementation of the first stage, not a claim of full agreement with the PWA archive interface. Physical runtime is mandatory to prove playback; unit tests and the build do not prove it. Server-side viewed writes during a smoke test change the user's status: run them only for an explicitly permitted test segment; otherwise check the recording without this mutation and note the limitation separately.
