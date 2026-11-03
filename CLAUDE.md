# Frigate Android

Нативный Android-клиент Frigate NVR. Эталон поведения и интерфейса — актуальный Frigate PWA, а не собственные упрощения.

Документы: [тесты и устройство](docs/testing-strategy.ru.md) · [BlackBox](blackbox/README.ru.md)

## Стек

- Kotlin, Jetpack Compose, Material 3, Media3, OkHttp, Gson (версии — `gradle/libs.versions.toml`). minSdk 31, compileSdk/targetSdk 35, id `com.frigate.android`.
- REST — `HttpURLConnection` (`core/server/FrigateClient.kt`); OkHttp — WebSocket и авторизованные снимки.
- Задачи решай текущим стеком; Hilt, Retrofit, Room, DataStore, Kotlin Serialization в проект не входят.
- Иконки: `material-icons-core` + `ui/icons/AppIcons.kt`. Новую иконку вне core копируй путь-в-путь из extended в `AppIcons.kt` и добавляй в `AppIconsTest` (extended в debug добавляет ~30 МБ dex).
- ABI: `arm64-v8a`, `armeabi-v7a` (`ndk.abiFilters`); запускай на ARM-устройстве, x86-эмулятор не подходит.
- Тесты: JUnit 5 на JVM; AndroidX/Compose instrumentation — JUnit 4.

## Команды

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH"   # Java 21 обязательна
./gradlew testDebugUnitTest assembleDebug          # unit suite + APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest --tests com.frigate.android.feature.live.MsePlaybackHtmlTest   # один класс
./gradlew assembleDebugAndroidTest assembleBlackbox   # instrumentation и BlackBox должны компилироваться
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk   # единственный способ ставить на устройство
```

Линтера в проекте нет: качество проверяют unit-тесты и компиляция всех таргетов.

## Устройство пользователя

- `connectedAndroidTest` не запускай: Gradle удалит приложение вместе с профилем, сессией и credentials. Ставь только `adb install -r`.
- App data, logcat и настройки устройства трогай только с разрешения.

## Проверка

- Баг-фикс: regression-тест → RED по правильной причине → минимальный фикс → GREEN.
- «Исправлено» пиши после свежего запуска, который подтверждает именно это утверждение.
- Изменения transport, playback и lifecycle проверяй ещё и на физическом устройстве ([docs/testing-strategy.ru.md](docs/testing-strategy.ru.md), раздел 4): unit-тесты и компиляция playback не доказывают.

## CI

- Перед пушем прогоняй `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleBlackbox` (то же, что `.github/workflows/ci.yml`).
- `versionCode` не увеличивай: иначе `adb install -r` на устройстве откажет.
