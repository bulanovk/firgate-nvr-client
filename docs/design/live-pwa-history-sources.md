**English** | [Русский](live-pwa-history-sources.ru.md)

# History: basis of the browser mockup

Status: a new variant after the simplified player was rejected; awaiting agreement. The Android code has not been changed. The accepted `live-unreviewed-carousel.html` is kept separately.

Mockup: [live-pwa-history.html](live-pwa-history.html).

## Studied source

Frigate upstream checkout: `<upstream-checkout>/frigate`. The paths below are relative to this checkout. Whether it matches the PWA version deployed for the user has not been established. The checkout code was not run.

| Scenario | Source | Reflection in the mockup |
| --- | --- | --- |
| Pressing a Live review | `web/src/components/card/AnimatedEventCard.tsx`, `web/src/types/review.ts` | The camera of the event, the start 4 s earlier; a local viewed mark when opened. Not a bounded clip. |
| History range | `web/src/pages/Events.tsx`, `web/src/views/recording/RecordingView.tsx` | On a normal entry, the last 24 hours; a separate day selection. The end of a review does not end the archive playback. |
| Mobile layout | `web/src/views/recording/RecordingView.tsx` | Back/Live on the left, the logo in the centre, camera/panel/settings on the right. The video is Fit; the panel is below in portrait, on the right in landscape. |
| Video controls | `web/src/components/player/HlsVideoPlayer.tsx`, `web/src/components/player/VideoControls.tsx` | A floating panel over the frame: sound, −10, play/pause, +10, speed, snapshot, fullscreen. No separate horizontal clip slider and no "Готово" (Done) row. |
| Cameras and modes | `web/src/components/overlay/MobileCameraDrawer.tsx`, `web/src/components/overlay/MobileTimelineDrawer.tsx` | Bottom drawers; changing the camera keeps the timestamp. Timeline / Events / Detail. |
| Archive timeline | `web/src/components/timeline/MotionReviewTimeline.tsx`, `web/src/components/timeline/MotionSegment.tsx`, `web/src/components/timeline/ReviewTimeline.tsx` | Newest at the top; a vertical timeline, motion/severity, recording gaps, a draggable red playhead, a scale of 30/15/5 s per segment. |
| Settings and filter | `web/src/components/overlay/MobileReviewSettingsDrawer.tsx`, `web/src/components/filter/ReviewFilterGroup.tsx` | Actions in a single column. The filter is by labels, not a second camera choice; apply/reset; the last selected label cannot be removed. A synthetic configuration without zones, sub-recording quality and admin/Frigate+. |
| Events and Detail | `web/src/components/card/ReviewCard.tsx`, `web/src/components/timeline/DetailStream.tsx` | The camera's events for the range and expandable groups with lifecycle seek. |

File names in the checkout are references for the study, not a guarantee of pixel-perfect matching. The Russian mode names were checked against `web/public/locales/ru/views/events.json`.

## Speed list discrepancy

The control panel in the mockup is taken from `VideoControls.tsx` — a shared component, but in the PWA itself it lives only in the live player (`HlsVideoPlayer.tsx`). The speed list in it is `PLAYBACK_RATE_DEFAULT = [0.5, 1, 2, 4, 8, 16]` by default, and the mockup repeats it.

The PWA's own recording view works differently: `Events.tsx` renders `RecordingView`, which works with `DynamicVideoPlayer`, where there is no `VideoControls` panel and no speed selection at all. The second view of an event — `web/src/views/events/EventView.tsx` — has its own Rate switch, but offers `[4, 8, 12, 16]` and starts at 8× (`useState(8)`), and its speed list is overridden in the `VideoControls` call.

Conclusion: the mockup repeats the PWA's shared component, not the unique speed set of the recording's on-screen view. This is a recorded discrepancy, not a build error; see the [verification of the carousel and History](../live-review-history-verification.md).

## Limits of verification

All frames and recordings are synthetic. Playback is a local timer, not HLS. There is no real audio, no requests to Frigate, no viewed writes, no download or export. Export, Share, Motion Search and Snapshot open honest explanations, not working PWA forms. Calendar is a simplified date input, not an exact copy of the calendar. Object boxes and all Detail scenarios are not implemented.

Checked: JS syntax; 13 controller/structure checks; 7 smoke scenarios of the full script on a DOM test double. This is not a real browser and not a check of layout/rendering, HLS or the Android runtime. A physical device and a production build were not used.

The range and filter regressions were first reproduced as RED, and after the change became GREEN. The generation script in the job temp is a historical draft: the final HTML was edited after generation; do not regenerate it over the mockup.
