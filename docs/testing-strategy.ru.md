**[English](testing-strategy.md)** | Русский

# Стратегия тестирования Frigate Android

Актуализировано: 2026-11-03. Текущий playback path — native go2rtc MSE / Media3, video-only. WebRTC playback в приложении нет. Второй playback path — архив HLS (Media3 `HlsMediaSource`) на экране History, он не связан с live-потоком.

Документ задаёт критерии, а не объявляет их выполненными. Результаты текущего изменения: [Live design review](live-design-review.ru.md), [верификация карусели и History](live-review-history-verification.ru.md). API-контракт: [Frigate Live protocol](frigate-live-protocol.ru.md).

## 1. Стек и границы доказательств

| Уровень | Инструмент | Что подтверждает |
|---|---|---|
| JVM unit | JUnit 5/Jupiter, JUnit Platform | Parsing, reducer, policy, session/output identity, шифрование |
| Transport integration | OkHttp MockWebServer | HTTP paths/cookies, WebSocket envelopes, MIME negotiation, byte order, close/errors |
| Compose UI | BOM-aligned ui-test-junit4, AndroidJUnitRunner (JUnit 4) | Semantics, state-driven overlays, snapshot visibility, Retry action |
| Physical device | Native MSE + ADB, свежие PID/время/screenshots | Первый действительно показанный кадр, compositing, sustained playback, lifecycle release |
| Black-box smoke / acceptance | UiAutomator (модуль `blackbox-tests`) + mock Frigate (`blackbox/`, [README](../blackbox/README.ru.md)) | Полный пользовательский путь и системные границы: вход и продление сессии, Live, History, видео (декодированный кадр синтетического H.264), звук, картинка в картинке, щипок, поворот, Events (список, превью карточки, сессия, Motion). 33 теста прогнаны на устройстве 2026-11-03 |

Compose-компонентные тесты лежат в `app/src/androidTest` и получают состояние презентации (плитка, панель камеры, зум, экран Events, его жесты, мутации и область движения). Сквозные сценарии «вход → транспорт → экран» выполняет UiAutomator против mock Frigate; screenshot goldens не реализованы. Нельзя объявлять green по JVM suite или запуску приложения. UiAutomator видит снаружи процесса экран, запросы к mock и системные границы (фон, занятый декодер в `dumpsys media.resource_manager`), но не заменяет Compose-проверки семантики и состояния.

## 2. JVM и transport

### Данные и policy

- ServerProfile: URL validation, origin/base path, запрет credentials/query/fragment.
- REST config/profile/events: defaults, optional fields, disabled cameras, detect dimensions, camera ID → stream mapping.
- CameraActivityReducer: camera+object identity; start/update/end одного объекта не удаляют остальные; motion, tracking, recording permission, detect/enabled независимы.
- Stationary labels остаются в displayed objects, но не поддерживают activeTracking.
- Live demand = visible && enabled && online && usable stream && (motion || tracking); recording OFF не запрещает live.
- Stats: offline только при `camera_fps == 0 && service.uptime > 60`; malformed/unknown data не затирают валидную availability. Обычная HTTP-ошибка не является offline.
- Visibility threshold 50%; hidden/disabled/offline немедленно снимают media demand.
- End activity: player сохраняется до 500 ms; новая активность отменяет остановку.
- Output readiness привязана к identity текущего output; READY не равен first frame, старый callback/detach не изменяет новый output.
- Snapshot polling: idle 30000 ms, starting 200 ms, live-ready 60000 ms, returning 300 ms; hidden/disabled/offline stop. Полный interval после load, 1 ms после slow load, error backoff минимум max(interval,1000).
- Геометрия: PWA buckets 16:9, 32:9, 8:9; picture Fit без обрезки.
- Session generation отвергает callbacks старого подключения после STOP/reconnect.
- Credentials/session encryption: origin isolation, corrupted ciphertext, wrong key, IV uniqueness, отсутствие plaintext.

### HTTP / WebSocket / MSE

- Initial stats используют authenticated `/api/stats`; session Cookie остаётся в заголовке, не URL. Redirect не пересылает авторизованный запрос на другой origin.
- Snapshot path корректно кодирует camera ID; query height ограничен; cache key меняется; `X-Frigate-Offline` разбирается отдельно от HTTP error. Cancellation отменяет OkHttp call.
- Activity socket отправляет `onConnect`, принимает camera_activity/events/motion/recordings/detect/enabled/stats и восстанавливается после reconnect. Поздний REST stats не должен затирать более свежий WS stats.
- MSE offer содержит только поддерживаемые video codecs. Ответ MIME и negotiated codec проверяются; audio не предлагается.
- DataSource сохраняет binary frame order; bounded queue не теряет байты молча; overflow/close/error разблокируют read.
- Buffer/catch-up policy проверяется независимо от UI. Редизайн не доказывает уменьшение network startup latency.

Существующий `MsePlaybackHtmlTest` — legacy regression, не подтверждение текущего UI playback. Старый `activeRecording` compatibility helper не является достоверным сигналом записи; REC в текущих плитках не показывается.

### Карусель непросмотренных alert и архив History

- Карусель Live показывает только alert-сегменты (`severity == "alert"`), не просмотренные, за последний час (окно PWA), отсортированные по времени начала по убыванию и без дублей по id.
- Обновление карусели по теме `reviews`: завершившийся alert — 1 s, новый или genai — 6 s, выросший список объектов — 5 s.
- Отметка просмотренным удаляет карточку только после успешного `POST /api/reviews/viewed`; ошибка запроса оставляет карточку на месте.
- Сверка карусели с сервером каждые 30 s (`REVIEW_RECONCILE_MILLIS`): карточка, чьё событие стало старше часа, уходит из полосы «Last hour» без нового WS-события; сверка останавливается вместе с сессией.
- Скорости воспроизведения совпадают с PWA: `0.5, 1, 2, 4, 8, 16`. Подпись — `${rate}x` без дробной части у целых значений («1x», «16x»).
- Меню скоростей — `DropdownMenu` (собственное окно popup) внутри элемента управления, а не дочерний элемент видео-бокса: in-place меню обрезается высотой кадра.
- Открытое меню скоростей не закрывается по таймауту скрытия панели управления (4 s), пока пользователь его не выбрал или не закрыл.
- Плейлист архива — `vod/{camera}/start/{after}/end/{before}/master.m3u8`; окно источника — часовой chunk якоря, поэтому перемотка внутри часа не перезапрашивает манифест; смещение inpoint вычитается из seek и возвращается в playhead.
- Границы часовых chunk — по часу UTC, как в PWA (`getChunkedTimeDay`): в UTC+5:30 chunk начинается на локальной половине часа. Тест `chunkedRangeCutsTheWindowAtTheUtcHourLikeThePwa` принудительно меняет зону JVM на `Asia/Kolkata` на время проверки — на машине с часами по UTC округление по локальной зоне прошло бы тот же тест, и расхождение с PWA осталось бы незамеченным.
- Конец записи внутри chunk переводит воспроизведение в следующий часовой chunk (`nextChunkForPlayback`): якорь переносится на начало следующего часа, playback продолжается; последний chunk окна завершает playback штатно, без ошибки.
- Пересборка источника (zoom, retry) продолжает с текущего playhead, а не откатывается на якорь; явный seek пользователя приходит на якорь.
- Смена камеры внутри архива сохраняет момент суток: playhead принадлежит сессии просмотра, а не камере.
- Запрос `GET /api/review` архива ограничен `HISTORY_REVIEW_LIMIT = 2_000`, тело любого ответа — `MAX_RESPONSE_BODY_CHARS = 16 MiB`; превышение даёт явную ошибку, а не OOM.
- Отменённая загрузка архива не превращается в ошибку панели: `CancellationException` пробрасывается, состояние архива принадлежит последней загрузке.
- Шкала прокручивается пальцем во время воспроизведения: слежение за playhead отключается прокруткой пользователя и возвращается по тапу.
- Тап по кадру обрабатывает сама поверхность видео (`HistoryTextureView.onTouchEvent` не поглощает событие): кликабельный слой дерева Compose поверх видео забирал попадание себе и оставлял кнопки панели управления неотзывчивыми.

## 3. Compose UI

`LiveCameraTileTest` проверяет:

1. Still виден при starting, исчезает только при videoReady и возвращается при media failure.
2. Имя, motion description и object labels присутствуют; REC отсутствует.
3. Disabled/offline имеют разные сообщения; Retry вызывает действие.
4. Настоящий opaque TextureView с цветным кадром остаётся под still до videoReady, затем виден под name overlay и rounded clip.

Тесты получают presentation state, поэтому **не доказывают**, что Media3 действительно показал кадр или что WebSocket доставил событие. Для полноценного E2E нужен управляемый Frigate test server и путь от envelope до semantics конкретной камеры.

Навигация Live (камера → Back → сетка, fullscreen и вкладки) и сохранность activity socket при смене вкладок проверяются снаружи процесса в UiAutomator (`LiveCameraTest`, `LiveFeedTest`): так проверяются настоящий `ConnectedApp`, его транспорт и системные границы, а не презентационное состояние.

Приложение разрешает cleartext во всех сборках: домашний адрес Frigate обычно обычный `http://` в локальной сети. Основной адрес обязан быть https (для локальных test server допустим loopback), session cookie и credentials уходят только на origin активного адреса, TLS verification не меняется.

Дополнительные acceptance-сценарии: portrait/wide, две колонки от 600 dp, длинное имя, overflow объектов, увеличенный шрифт, clipping snapshot/TextureView и overlays. Цвет не должен быть единственным признаком состояния; Retry touch target минимум 48 dp.

## 4. Physical device acceptance

Проверять только со свежими PID/временем, `mWakefulness=Awake`, фокусом MainActivity и открытым screenshot. Непустой PNG сам по себе ничего не доказывает.

Матрица:

- Сохранённая сессия → Connected → реальные snapshots.
- Idle → motion/tracking → native MSE first frame: still остаётся до текущего output frame, без чёрного промежутка.
- Sustained playback; frame/time progress сопоставлены с transport delivery. Отсутствие transport-логов само по себе не доказывает обрыв.
- End/stationary → 500 ms grace → still; новая активность во время grace сохраняет player.
- Несколько активных камер; scroll не пересоздаёт остающийся видимым player, ушедший за threshold освобождается.
- Media failure → still + Retry → свежая session/output identity.
- Disabled/offline/recovery по достоверным данным.
- Events/Settings: release media и отмена polling, но shared activity subscription сохраняется на уровне ConnectedApp. Background/screen off/on/pause/resume: ON_STOP закрывает оба транспорта, ON_START создаёт свежую generation.
- Домашний адрес: в домашней Wi-Fi приложение работает по домашнему адресу, в мобильной сети и любой другой Wi-Fi по основному; в Настройках (режим просмотра; Edit/Save/Cancel; смена основного адреса требует войти заново) видно, какой адрес активен. Переключение сохраняет сессию (без нового входа), пересоздаёт activity socket и players, cookie уходит только на активный origin. Недоступный домашний адрес откатывается на основной до смены сети. Отзыв разрешения на геолокацию возвращает основной адрес. Переключение проверять во время Live, History и picture-in-picture.
- DIM при STARTED проверяется отдельно: это не STOP, и потеря событий не считается устранённой layout-изменением.

Не подменять один playback path другим ради green.

### Архив History

Матрица архива:

- Тап по карточке карусели открывает History: позиция = начало события − 4 s, шкала построена, первый кадр HLS декодирован (кадр с OSD камеры виден, не чёрный прямоугольник).
- Sustained playback: позиция идёт непрерывно, повторного BUFFERING нет; отсутствие transport-логов само по себе обрыв не доказывает.
- ±10 s: точное перемещение, на паузе позиция не дрейфует.
- Перемотка ±10 s внутри часового chunk: картинка не гаснет и не перебуферизуется, манифест не перезапрашивается; позиция и OSD камеры идут синхронно.
- Перезагрузка архива (zoom шкалы, retry): playhead сохраняется, видео продолжает идти, отката на якорь нет.
- Прокрутка шкалы пальцем во время воспроизведения: содержимое остаётся на месте, автоскролл к playhead не возвращает его.
- Перемотка по шкале переходит на выбранный момент.
- Смена камеры сохраняет момент суток.
- Back из History возвращает сетку Live и её позицию; уход с Live освобождает плеер архива.
- Снимок кадра: JPEG в `Pictures/Frigate` и подтверждение в UI.
- Меню скоростей: открывается по тапу, содержит все шесть скоростей PWA, читаемо на тёмном фоне, переживает таймаут скрытия панели (4 s); выбранная скорость реально применяется (позиция уходит быстрее реального времени).
- Кнопки панели управления отвечают на тап: Pause останавливает картинку, mute меняет иконку, ±10 s двигают позицию, «1x» открывает меню скоростей, снимок кадра и fullscreen срабатывают. Проверять тапом сразу после показа панели: она скрывается через 4 s, а `screencap` по ADB занимает дольше, и тап успевает попасть по голой картинке.
- Переход в следующий часовой chunk по окончании записи: playhead переходит границу часа без разрыва, OSD камеры идёт непрерывно, в logcat видна смена источника; если в следующем chunk у камеры нет записи, playback останавливается штатно, без сообщения об ошибке.
- Lifecycle ON_STOP/ON_START и screen off/on — те же требования, что и для live: media освобождается, транспорты закрываются, новая generation создаётся.

Замечание по инструментации: `adb shell uiautomator dump` между двумя тапами поглощает следующий тап (артефакт обхода accessibility) и даёт ложный диагноз «кнопка не реагирует». Для последовательных тапов использовать `screencap` без dump.

Экран History достижим только через карусель непросмотренных alert. Если таких alert за последний час нет, он недостижим из UI — это ограничение точки входа, а не дефект playback; проверка архива требует либо реального alert, либо временной обвязки, которая обязана быть отменена до сборки.

## 5. Команды и безопасный device workflow

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest assembleDebug
```

Targeted JVM:

```bash
./gradlew testDebugUnitTest --tests '*LiveTilePolicyTest'
```

На **выделенном тестовом** устройстве без пользовательской сессии:

```bash
ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest
```

Gradle connected tests могут uninstall target APK при cleanup, затронув данные приложения. Не запускать этот workflow на пользовательском устройстве с сохранённой конфигурацией без отдельного разрешения на этот эффект. Для недеструктивной проверки использовать install-in-place и прямой runner:

```bash
ANDROID_SERIAL=<serial> ./gradlew installDebug installDebugAndroidTest
adb -s <serial> shell am instrument -w -r \
  -e class com.frigate.android.feature.live.LiveCameraTileTest \
  com.frigate.android.test/androidx.test.runner.AndroidJUnitRunner
```

Не очищать app data/logcat, не uninstall и не менять настройки устройства без разрешения. При нескольких targets всегда указывать serial. Credentials и media payload не выводить; test secrets не хранить в репозитории.

```bash
adb -s <serial> shell am start -n com.frigate.android/.MainActivity
adb -s <serial> shell dumpsys power
adb -s <serial> shell dumpsys window
adb -s <serial> exec-out screencap -p > "$CLAUDE_JOB_DIR/tmp/live-runtime.png"
PID=$(adb -s <serial> shell pidof com.frigate.android | tr -d '\r')
adb -s <serial> logcat -d -v threadtime --pid="$PID" \
  | grep -E 'FrigateNativeMSE|CameraActivity|FATAL EXCEPTION'
```

CI gates: JVM+transport green, Compose UI green на API31+, debug build; отдельно physical native MSE acceptance. Нереализованный или заблокированный слой отмечается явно, а не засчитывается по соседнему green.

## 6. Источники

- https://github.com/junit-team/junit-framework
- https://github.com/android/android-test
- https://developer.android.com/develop/ui/compose/testing
- https://github.com/square/okhttp/tree/master/mockwebserver
- https://github.com/blakeblackshear/frigate
