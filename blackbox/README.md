English | **[Русский](README.ru.md)**

# Frigate Android BlackBox tests

External UiAutomator tests (module `:blackbox-tests`): the installed APK, the real screen, the user path. They know nothing about app internals and do not replace the JVM, Compose and device checks in the [testing strategy](../docs/testing-strategy.md).

| Path | Purpose |
|---|---|
| `../blackbox-tests/` | UiAutomator tests: login, session renewal, carousel → History, Live (camera, fullscreen, rotation, pinch, activity, stills), video and sound, Events (list, card preview, session, Motion), home network (address by Wi-Fi: switch, fallback, unknown network, no permission) |
| `mock-server/make_media.py` | test H.264 generator (needs PyAV, key `BB_MEDIA_PYTHON`) |
| `mock-server/server.py` | controllable mock Frigate (Python, standard library only) |
| `lib/config.sh` | local settings loader |
| `run.sh` | runner: mock, `adb reverse`, `adb install -r`, `am instrument`, cleanup |
| `blackbox.local.env.example` | local settings template |

## Run

1. Copy `blackbox.local.env.example` to `blackbox.local.env`, fill it in, `chmod 600`. Or point `BLACKBOX_CONFIG` at another file. Environment variables override the file.
2. Build: `./gradlew assembleBlackbox :blackbox-tests:assembleBlackbox` (Java 21).
3. `blackbox/run.sh`, or `blackbox/run.sh LoginTest` for one class.

There are no network defaults: host, port, device serial and server URL come only from the local file; a missing key stops the run.

`blackbox/run.sh Class` or `blackbox/run.sh Class#method` runs one class or one test.

## Mock control

The mock is driven through `/__control/*`, always over loopback: `review/<epoch>` (fixed start of the review), `reviewed/on|off`, `timezone/<name>`, `cameras/<n>`, `preview/<video|none>`, injected failures `fail/<viewed|preview-media|thumb>/<code>/<times>`, held responses `hold/<preview-media|motion>/on|off`, `activity` and `ws/send` (WebSocket pushes), `audio/<mode>`, `expire`, `reset`, the request log `log` and the `Host` headers of requests `hosts`/`reset-hosts` (without query strings, without `/__control/*`, up to 500 entries). Tests count requests from that log.

A held video decoder is visible from outside the app process in `dumpsys media.resource_manager` (`Current Codec Usage: { VideoDec[ HW: n ] }`); it is present only while a decoder is held.

## Data safety

- The `blackbox` build is a separate app (`.blackbox` suffix); the main app's profile and session are untouched. Install is `adb install -r` only.
- Test login and password are read from the mock over loopback, not from arguments.
- TLS is not disabled.

## Limits

The mock serves video only when `BB_MEDIA_PYTHON` is set, and a frame is checked by screenshots. Real cameras, H.265, multi-hour playback and the archive speed menu stay with device acceptance in §4 of the strategy. Backgrounding the app (HOME and return) is checked; screen off/on is not.

## Test rules

- A rotation test must request an orientation the activity does not have yet: requesting the current one does not recreate the activity, and the test passes while proving nothing. `ActivityInfo.SCREEN_ORIENTATION_*` and `Configuration.ORIENTATION_*` are different constants; do not compare them directly. The framework performs the rotation (`UiDevice.setOrientationLeft/Natural`) and freezes auto-rotate, so the test calls `unfreezeRotation()` in `@After`.
- The video tests use the H.264 1280×720 clip from the mock (`mock-server/make_media.py`); without `BB_MEDIA_PYTHON` they are skipped. The 1280 px width is also needed for zoom: the limit is computed from the source width, and at 320 px there is no zoom.
- The Compose accessibility state (`stateDescription`) is not visible through UiAutomation; such state is read from pixel colours.
- Long tests hold the screen on themselves (`keepScreenOn()`): after ~30 s without touches it turns off and playback stops.
- `adb shell uiautomator dump` between two taps swallows the next tap and gives a false "the button does not react" diagnosis. Use `screencap` without a dump for consecutive taps.
- Text goes into fields directly (`UiObject2.text`), without the keyboard: the Samsung keyboard covered the form.
