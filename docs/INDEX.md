**English** | [Русский](INDEX.ru.md)

# Frigate Android documentation — map

Checked 2026-11-02 against the code (`app/src/main`). Statuses:

- **Current** — describes the current state; if it disagrees with the code, the document is corrected.
- **Reference** — external or reference material (the Frigate protocol, PWA source excerpts).
- **Snapshot** — a report or plan for a date; it records a past state and is not updated. The current state is in CLAUDE.md and the "Current" sections.

The main project rule is in [CLAUDE.md](../CLAUDE.md): architecture, transport, camera policy, security, testing, and limitations.

Most documents below are written in Russian. Where a `*.ru.md` version exists, see the link in the Russian index.

## Current

| Document | About |
|---|---|
| [testing-strategy.md](testing-strategy.md) | Test strategy, acceptance criteria, how to work with the device. |
| [live-eager-cards-design.md](live-eager-cards-design.md) | Live: all camera cards are composed at once; implemented 2026-11-02. |
| [live-camera-panel-review.md](live-camera-panel-review.md) | Live camera panel, player zoom, differences from the PWA. |
| [live-review-history-verification.md](live-review-history-verification.md) | Review carousel and History archive: what was verified on a device and what was not. |

## Reference

| Document | About |
|---|---|
| [frigate-live-protocol.md](frigate-live-protocol.md) | Frigate Live protocol: REST, WebSocket, go2rtc MSE/WebRTC. |
| [live-design-review.md](live-design-review.md) | Live design review: image and status hierarchy. Partly a snapshot, see below. |
| [design/](design/) | Mockups and reference excerpts from the PWA: camera panel, history, events, carousel. `*.test.cjs` are checks of the HTML mockups. |

## Snapshots (past states)

| Document | Date | What it records |
|---|---|---|
| [live-navigation-regressions.md](live-navigation-regressions.md) | 2026-10-29 | Tap and status regressions when switching tabs, and their fixes. |
| [live-cold-start-investigation.md](live-cold-start-investigation.md) | 2026-10-29 | Investigation of activity recovery after a restart; the cause is not established. |
| [superpowers/specs/](superpowers/specs/) | 2026-10-28 … 2026-11-01 | Specifications the code was written from. |
| [superpowers/plans/](superpowers/plans/) | 2026-10-28 … 2026-11-01 | Plans with checkboxes. The checkboxes are the state on that date, not a current task list. |

## What changed in the 2026-11-02 review

- Device address and serial number removed; the home network layout replaced with placeholders (`<device-serial>`, `<frigate-lan-ip>`, `<vps-public-ip>`, `<wg-subnet>`, etc.); local checkout paths replaced with `<upstream-checkout>`.
- `live-eager-cards-design.md` moved from "proposal" to "implemented", with a list of checks.
- CLAUDE.md now states that all Live cards are composed at once.
- Historical documents are marked as snapshots; their content was not rewritten.

## Known inconsistencies, not fixed here

- Plans and specifications from 2026-10-30 describe `LazyGridState` in `ConnectedApp`. That is a snapshot of a past state; the current Live scroll state is `ScrollState` (see live-eager-cards-design.md).
- The comparison with the PWA is done against a checkout of the source, whose version has not been matched to the server the user runs (see live-camera-panel-review.md).
