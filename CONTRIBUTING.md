**English** | [Русский](CONTRIBUTING.ru.md)

# How to Contribute

Thank you for your interest in the project. Please read this document before opening a pull request.

Feature design, protocols, and device checks are described in [docs/](docs/). The file [CLAUDE.md](CLAUDE.md) is the instruction file for the AI agent that works with the code in this repository.

## Before you start

### Bug fixes

If you found a bug and want to fix it, go ahead. Link the related issue in your PR if there is one, or describe the bug and the steps to reproduce it in the PR description.

### New features

A pull request is a request for maintainers to review, accept, and support a change. Larger changes need agreement before code is written: describe the idea in an issue first, then write the code.

Before you write code for a new feature:

1. Search the existing issues and discussions for similar proposals.
2. Open an issue describing the problem and the proposed solution.

## Pull requests

### Before submitting

- **Search for existing PRs** so you don't duplicate work.
- **Test your changes locally.** The build, unit tests, and, if reproduction or the activity lifecycle is affected, a check on a device.
- **One task per PR.** A bug fix and a new feature should be separate PRs.

### What reviewers check

- **Does it work?** Tested locally, tests pass, no regressions.
- **Is it maintainable?** Clear code, reasonable complexity, good separation of responsibilities.
- **Does it fit?** Consistent with the feature design and project principles described in `docs/`.
- **Is the scope right?** Solves the stated problem without unnecessary additions.

### After submitting

- Respond to review comments.
- If your PR goes stale, update it on the current main branch.

## Build and tests

You need JDK 21 and the Android SDK. Run these from the project root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

### Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `main`, to branches matching `ci/*` and to `v*` tags, and on every pull request. It runs the unit tests, builds the debug APK, and checks that the instrumentation and BlackBox tests compile. Please make sure your pull request is green before asking for a review. Tests that need a device are not run in CI. After a green push to `main`, CI also publishes a debug-signed test APK as a GitHub pre-release (`test-<run number>`); it is for testing only, not for production.

## Coding standards

- **Kotlin and Compose**: follow the style of the existing code. Do not add a library if the current stack already solves the problem.
- **Tests**: JVM tests use JUnit 5; instrumentation tests use JUnit 4.
- **Security**: do not log or publish cookies, passwords, PINs, or URLs containing secrets. Do not disable TLS verification and do not use trust-all. Never put credentials in a URL.
- **Documentation**: do not put device serial numbers, internal IP addresses, or home-network topology in `docs/`; use placeholders (`<device-serial>`, `<frigate-lan-ip>`).

## Fixing a bug in code

1. Write a minimal test that reproduces the bug.
2. Run it and confirm it fails for the right reason.
3. Make a minimal change to the code.
4. Run the test again and confirm it passes.
5. Run the full unit test suite and build the debug APK.

Changes that touch video playback, WebSocket, WebView, or the Android lifecycle are not confirmed by compilation and unit tests alone: check the behavior on a physical device.

## Tests on a device

Do not run `connectedAndroidTest` on a phone where your app is installed: Gradle removes the app together with its data (server profile, session, stored credentials). Install and run instrumentation tests like this:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  -e class com.frigate.android.feature.live.LiveCameraTileTest \
  com.frigate.android.test/androidx.test.runner.AndroidJUnitRunner
```

If several devices are connected, always pass `-s <serial>`.

End-to-end device scenarios (login, Live, History, Events, video, rotation) are UiAutomator tests in `blackbox-tests` run against a mock Frigate with `blackbox/run.sh`; see [blackbox/README.md](blackbox/README.md). They install a separate `.blackbox` app, so the data of the main app is not touched.

## Use of AI tools

If you used AI tools, you are responsible for the code you submit: review it, test it, and be able to explain every line.
