# Frigate Android

## Назначение проекта

Нативное Android-приложение для Frigate NVR, повторяющее основной сценарий актуального Frigate PWA.

Текущий MVP:

- Android 12+;
- экраны Live, Events и Settings;
- подключение к одному Frigate-серверу по URL по умолчанию;
- авторизация и повторное использование сессии;
- список камер и событий;
- Live dashboard с native go2rtc MSE (video-only, Media3, без WebView);
- authenticated `latest.webp` snapshots; live запускается для видимых активных камер;
- независимые motion/tracking-индикаторы и пиктограммы объектов, включая stationary. Разрешение записи не определяет запуск live;
- карусель непросмотренных alert наверху Live (сценарий PWA) и экран архива History, открываемый тапом по карточке карусели.

Будущая функция: отдельные URL Frigate для выбранных Wi-Fi-сетей. Wi-Fi routing пока не реализован и не должен добавляться в MVP без отдельной задачи.

## Платформа и стек

- Kotlin 2.2.10;
- Android Gradle Plugin 9.4.1;
- compileSdk/targetSdk 35;
- minSdk 31 (Android 12);
- Jetpack Compose с Compose Compiler Plugin;
- Compose BOM 2024.12.01;
- Material 3 и Material Icons (core); остальные иконки лежат в проекте (см. ниже);
- Kotlin Coroutines из Android/Compose runtime;
- Java 21 для Gradle-команд в текущем окружении;
- ABI: только `arm64-v8a` и `armeabi-v7a` (`ndk.abiFilters` в `app/build.gradle.kts`); x86 и x86_64 в APK не попадают, на x86-эмуляторе приложение не запустится.

Основной namespace и application id: `com.frigate.android`.

## Выбранные библиотеки

Версии зафиксированы в `gradle/libs.versions.toml`:

- `androidx.core:core-ktx:1.15.0`;
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`;
- `androidx.activity:activity-compose:1.10.0`;
- `androidx.compose:compose-bom:2024.12.01`;
- `androidx.compose.material3:material3`;
- `androidx.compose.material:material-icons-core` — базовый набор иконок. Иконки вне core лежат в `ui/icons/AppIcons.kt` как extension-свойства `Icons.Outlined.X` / `Icons.AutoMirrored.Outlined.X`: пути скопированы из `material-icons-extended` один к одному. Саму `material-icons-extended` не подключать: в debug-сборке она добавляет в APK около 30 МБ dex ради ~30 используемых иконок. Нужна ещё одна иконка вне core — добавить в `AppIcons.kt` и в `AppIconsTest`;
- `com.squareup.okhttp3:okhttp:4.12.0` — WebSocket transport and authenticated snapshot HTTP;
- `androidx.media3:media3-exoplayer:1.9.0`, `androidx.media3:media3-ui:1.9.0` — native MSE playback;
- `com.google.code.gson:gson:2.11.0` — разбор JSON REST-моделей;
- `org.junit:junit-bom:5.14.1` — версии JUnit 5/Jupiter и JUnit Platform;
- `org.junit.jupiter:junit-jupiter-api`, `junit-jupiter-params` — JVM unit-тесты;
- `org.junit.jupiter:junit-jupiter-engine`, `org.junit.platform:junit-platform-launcher` — test runtime.

Не считать частью текущего стека библиотеки из первоначального плана, отсутствующие в Gradle: Hilt, Retrofit, Room, DataStore и Kotlin Serialization.

## Архитектура и транспорт

### Compose UI

`app/src/main/java/com/frigate/android/MainActivity.kt` содержит корневое Compose-приложение, состояния подключения и экраны:

- `Loading`;
- `LoginRequired`;
- `Connected`;
- Live;
- Events;
- Settings.

`ConnectedApp` владеет `rememberCameraActivity` (`CameraActivitySession.kt`): activity WebSocket продолжает получать статусы при переключении Live/Events/Settings, останавливается при Activity STOP или выходе из подключённой сессии. `feature/live/LiveCameraScreen.kt` владеет viewport, snapshot cache и pool native MSE players. Все карточки камер компонуются сразу (колонка с `verticalScroll`, как карточки PWA): невидимая карточка получает один снимок, опрос снимков и видео — только для видимых. Tap плитки открывает выбранную камеру с явным live независимо от motion; Back возвращает сетку. `LiveCameraTile.kt` рисует PWA-подобные плитки: одна колонка на compact, две при ширине от 600 dp, Fit и aspect buckets 16:9 / 32:9 / 8:9. В сетке live разрешён для enabled/online камер, видимых минимум на 50%, при motion или non-stationary tracking. Snapshot остаётся поверх TextureView до первого кадра текущего Surface. REC не отображается: motion/tracking не доказывают фактическую запись.

### Поворот экрана

Сессия приложения живёт в композиции `MainActivity`: activity WebSocket, players камер, выбранная камера (`liveCameraId`) и навигация архива (`HistoryNavigation`). Если система пересоздаёт activity при повороте, вся композиция теряется и пользователь возвращается на сетку Live вместо открытой камеры или архива.

`MainActivity` поэтому объявляет `android:configChanges` (`orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|uiMode|density|fontScale|layoutDirection|locale`) и обрабатывает изменение конфигурации сам: activity не пересоздаётся, композиция, socket и players переживают поворот. Проверка: JVM-тест `MainActivityManifestTest` (разбирает манифест) и BlackBox-тесты `LiveCameraTest` и `VideoPlaybackTest` (поворот выполняет фреймворк: открытая камера остаётся открытой и видео идёт).

Следствие для live: TextureView переживает поворот вместе с composition, поэтому `MseTextureView` получает новый Surface и заново привязывает его к тому же player — поток продолжается без пересоздания player.

### REST

`core/server/FrigateClient.kt` использует `HttpURLConnection` для:

- `POST /api/login`;
- `GET /api/profile`;
- `GET /api/config`;
- `GET /api/events`;
- `GET /api/stats` — начальная availability после открытия activity socket.

`FrigateRepository` предоставляет suspend-обёртки над HTTP-вызовами. Все URL строятся относительно настроенного `ServerProfile`.

### Frigate WebSocket

`feature/live/CameraActivityWebSocket.kt` подключается к `/ws`, отправляет envelope `onConnect` и обрабатывает:

- `camera_activity`;
- `<camera>/motion`;
- `<camera>/recordings/state`;
- `<camera>/detect/state`;
- `<camera>/enabled/state`;
- `stats`;
- `events`.

WebSocket используется для runtime activity и индикаторов, а REST — для начального состояния и списка событий. После reconnect WebSocket не считается журналом событий: начальное состояние должно восстанавливаться через REST.

### MSE: текущий live-путь

Текущий UI использует native go2rtc MSE без WebView; дорожка звука выключена, пока пользователь не включит её кнопкой панели камеры. `CameraSnapshotLoader` запрашивает authenticated `GET /api/<camera>/latest.webp?height=…&cache=…`; player создаётся по visibility/activity policy, а не по разрешению записи. Неподвижные объекты остаются в overlays, но не поддерживают activeTracking.

Поток playback:

1. `LiveCameraScreen` создаёт `NativeMsePlayer` и `Go2RtcMseDataSourceFactory`; `LiveStreamEndpoint.mseWebSocketUrl` строит `ws`/`wss` URL относительно `ServerProfile` для `/live/mse/api/ws?src=<encoded stream>` (Frigate проксирует запрос к go2rtc `/api/ws`). Camera ID и stream name могут различаться. Session cookie передаётся только в HTTP-заголовке `Cookie`, не в URL.
2. `Go2RtcMseProtocol.deviceVideoCodecOffer()` использует decoder capabilities устройства и предлагает только поддерживаемые H.264/H.265 candidates видео и поддерживаемые аудио-candidates (AAC, FLAC, Opus). DataSource отправляет JSON MSE offer, проверяет MIME response и что negotiated video codec входит в offer.
3. `Go2RtcMseDataSource` превращает упорядоченные бинарные WebSocket frames в Media3 byte stream неизвестной длины. `MseByteQueue` ограничена 2 MiB: при переполнении данные не выбрасываются и stream завершается явной ошибкой. Закрытие DataSource закрывает socket и разблокирует read; автоматического reconnect нет — повторное подключение выполняется повторным открытием камеры.
4. `NativeMsePlayer` использует ExoPlayer с `ProgressiveMediaSource` и единственным `FragmentedMp4Extractor`. `NativeMseVideo` встраивает TextureView через `AndroidView` и владеет Surface. Readiness требует `AnalyticsListener.onRenderedFirstFrame` для точного текущего Surface и texture update; `STATE_READY` не скрывает still. Detach/error сбрасывают readiness; ошибка оставляет snapshot и явный Retry.
5. `MseLiveBufferPolicy` задаёт минимум буфера 5 s, максимум/retained window 15 s, старт и повторный старт при 1 s. Catch-up speed остаётся 1.0× при lag до 2 s, затем растёт линейно до 2.0× при lag 10 s и выше. Deadband нужен для near-live потока: ускорение при малом запасе истощало буфер и вызывало периодические rebuffer.

Lifecycle: pool сохраняет player для остающейся видимой камеры; stream/session/retry меняют identity. Конец активности даёт 500 ms grace; hidden/disabled/offline останавливают media сразу. В detail выбранная камера получает explicit live demand без motion gate. Уход с Live освобождает players и останавливает snapshot polling, но не shared activity socket. ON_STOP освобождает media и останавливает shared socket; ON_START создаёт новую generation. `MseLivePlayer`/HTML — reference-only legacy WebView path, не acceptance path текущего UI.

### Панель live-камеры и зум плеера

Тап по плитке открывает `feature/live/LiveCameraPanel.kt` — отдельный экран одной камеры по форме PWA (`LiveCameraView`), а не карточка 16:9 под заголовком. Панель — это шапка 48 dp + кадр по реальному аспекту камеры (`aspects[playerKey]`, иначе `detectWidth/detectHeight`), чёрные поля сверху и снизу. Имя камеры в панели не показывается: в PWA оно есть только у оффлайн/отключённой камеры, в плитках сетки остаётся.

Шапка повторяет две раскладки PWA, где выбор решает ширина экрана (`maxWidth >= 600.dp` → `expanded`):

- узкий экран: Back («Back to cameras»), History («Open history»), справа fullscreen, картинка в картинке, звук (по возможностям потока) и шестерёнка; шестерёнка открывает `ModalBottomSheet`;
- широкий экран: те же кнопки с подписями «Back»/«History», снимок «Take a snapshot» и ручная запись (только admin) перед шестерёнкой; шестерёнка открывает `DropdownMenu`;
- полный экран: шапка уходит с экрана и становится плавающим рядом справа сверху (градиентный Back + «Leave fullscreen», на широком ещё снимок и ручная запись); шестерёнка скрыта в обеих раскладках.

Внешний вид шапки берётся из утверждённого `docs/design/live-camera-panel.html`: кнопки 36×36 dp, радиус 8 dp, контурные иконки 21 dp (`LiveCameraHeaderIcons` содержит SVG-пути макета), фон действий `#26282d`, Back/History без заливки. Группы разделены интервалами 6/3 dp. Не возвращать стандартные Material Icons и размеры IconButton вместо макета.

Число кнопок демонстрационного макета не фиксирует состав шапки. PWA скрывает звук/микрофон по возможностям потока из go2rtc metadata, PiP — по платформе/режиму, admin-функции — по роли и конфигурации. Android читает metadata потока (`liveStreamInfo` → `audioOutput`, `twoWayAudio`): кнопка звука появляется только когда поток объявил приём звука, дорожка звука выключена по умолчанию (`setAudioEnabled`) и включается кнопкой, а через 5 с без декодированного звука выключается снова с сообщением «This camera has no audio». Микрофон (двусторонняя связь), PTZ, низкая полоса и воспроизведение в фоне не реализованы — их кнопок нет. Мёртвые кнопки в приложении не рисуются: отсутствие кнопки не означает отсутствия возможностей у камеры.

Состав настроек (лист снизу на узком экране, выпадающее меню на широком): admin-тумблеры камеры (`cameraControls`; часть — только если включена в конфиге), выбор потока, список движков, ручная запись → [«Take a snapshot»][«Start recording»] (вторая кнопка — admin), «Statistics», «Debug view». Снимок кадра уменьшается до 1920 px по ширине (`saveFrameToGallery`). Низкая полоса (JSMpeg) и воспроизведение в фоне в PWA есть, у нас нет.

Список «Режим» — по эталону PWA (`StreamTechnologySelect.tsx`): ровно два варианта, MSE и WebRTC. MSE доступен; WebRTC всегда в списке, но приглушён с причиной, потому что пути WebRTC playback у приложения нет. MJPEG движком не является — в PWA это переключатель низкой полосы (`LiveCameraView.tsx:267`). Список задан чистой функцией `liveEngineOptions()` (`feature/live/LiveEngineOptions.kt`) — JVM-тестируемо, без Android-импортов.

Ручная запись (`ManualRecordingSession`) переживает закрытие панели: незакрытое событие должно быть закрыто, иначе запись идёт дальше. 401 от записи или от тумблера ведёт в `SessionRenewal`, а не на экран входа.

Состояние fullscreen поднято в `LiveCameraScreen`: `BackHandler` сначала выходит из полного экрана и только потом закрывает камеру — как `navigate(-1)` в PWA.

Зум — **свойство плеера**, а не фича live-экрана (решение владельца): общий слой `feature/player/`.

- `PlayerZoom.kt` — чистая модель (JVM-тестируемая): предел `clamp(3 × sourceWidthPx / viewportWidthPx, 1, 10)`, то есть ~3 экранных пикселя на один исходный; неизвестна ширина кадра — fallback 4×; зум вокруг точки касания; смещение зажато границами `(scale−1)×размер/2`;
- `PlayerZoomState.kt` — состояние и два модификатора: `playerZoomGestures` (щипок, протяжка, двойной тап → сброс) и `playerZoomTransform` (`graphicsLayer` + `clipToBounds`). Жесты считаются вручную, а не через `detectTransformGestures`: тот отдаёт накопленные значения, а нужны шаги; slop считается от начала жеста, иначе медленная протяжка его не пересекает;
- картинка несёт `contentDescription = "Zoom 1.0x"` — это и доступность, и признак того, что щипок дошёл до кадра, в instrumentation-тестах.

Картинка и её оверлеи масштабируются вместе, шапка и панель управления архива — вне слоя зума и не масштабируются (как в PWA). Жесты живут в Compose, а поверхность кадра (`TextureView`) касание не поглощает — правило D6 не нарушено. Поверхность держит кадр в исходном разрешении (`SurfaceTexture.setDefaultBufferSize`): иначе зум растягивал бы уже уменьшенный кадр и деталей не добавлял бы.

Зум подключён к live-панели и к архиву (`HistoryVideoPlayer`). Смена камеры и новый источник в архиве сбрасывают зум. Ревью и расхождения с PWA: [docs/live-camera-panel-review.md](docs/live-camera-panel-review.md).

### Карусель review и архив History

`feature/review/` повторяет карусель непросмотренных alert наверху Live. `ReviewUpdates` разбирает тему `reviews` WebSocket и задаёт каденс обновления как в PWA: завершившийся alert — 1 s, новый или genai — 6 s, выросший список объектов — 5 s. `unreviewedAlerts()` оставляет только `severity == "alert"`, не просмотренные, за последний час, без дублей по id, по убыванию времени начала. Превью грузятся авторизованно из `thumb_path`; путь проверяется на префикс `/media/frigate/clips/review/` и не может выйти за пределы папки review. Отметка просмотренным — `POST /api/reviews/viewed`; карточка уходит из карусели только после успешного ответа. Кроме WebSocket список сверяется с сервером по таймеру `REVIEW_RECONCILE_MILLIS = 30_000L`: карточка, событие которой стало старше часа, должна уйти из полосы «Last hour» сама.

`feature/history/` — экран архива: `HistoryScreen` (шапка, видео, панель timeline/events/detail, ящики камеры, панели и настроек), `HistoryNavigation` (сессия просмотра: камера, панель, диапазон, фильтр, playhead), `HistoryRange` (окно по умолчанию и по дням), `HistoryTimelineModel` (сегменты шкалы), `HistoryPlaybackModel` (окно источника и inpoint).

Поток playback архива:

1. Плейлист — `ReviewMediaEndpoint.vodPlaylistUrl()`: `{base}/vod/{camera}/start/{after}/end/{before}/master.m3u8`. Это nginx vod-модуль вне префикса `/api/`. Media3 `HlsMediaSource` + `ExoPlayer`; session cookie передаётся в заголовке `Cookie` через `HistoryHlsDataSourceFactory`.
2. Окно источника — **часовой chunk якоря** (`historyWindow`/`chunkFor`): playlist запрашивается на час записи и держится открытым, поэтому перемотка внутри часа не перезапрашивает манифест и не перебуферизует картинку. Якорь двигает только явный seek пользователя; пересборка источника (zoom, retry) продолжает с текущего playhead, а не откатывается на якорь. Смещение inpoint вычитается из seek и возвращается в playhead.
3. Границы часовых chunk — **по часу UTC**, как в PWA (`getChunkedTimeDay` → `setUTCMinutes(0,0,0)`): `floorToHour` в `HistoryRange.kt`. В UTC+5:30 граница приходится на локальную половину часа — это поведение PWA, а не дефект; округление по локальной зоне отличалось от PWA (R9).
4. Playhead хранится в wall-clock, поэтому смена камеры сохраняет момент суток.
5. Конец записи внутри chunk — **переход в следующий chunk**: `STATE_ENDED` → `nextChunkForPlayback` → якорь переносится на начало следующего часа, воспроизведение продолжается (как `onClipEnded` в PWA). Последний chunk окна завершает playback штатно, без ошибки.
6. Скорости — `HISTORY_SPEEDS = 0.5, 1, 2, 4, 8, 16`, как в PWA; подпись `historyRateLabel(speed)` даёт `1x`, `16x`. Меню скоростей — `DropdownMenu` в собственном окне popup: in-place меню обрезается высотой видео-бокса и нижние скорости недостижимы. Всплывающее окно рисуется по `historyMenuColorScheme` (тёмная схема из `HistoryColors`), потому что у приложения нет собственной Material-темы и дефолтная светлая сделала бы текст нечитаемым. Состояние меню поднято в `HistoryVideoPlayer`: пока меню открыто, панель управления остаётся собранной и не закрывается по таймауту `HISTORY_CONTROLS_TIMEOUT_MILLIS = 4_000L` (в PWA мобильная панель прячется через 4000 ms — тот же приём).
7. Тап по кадру живёт **в поверхности** (`HistoryTextureView.onTouchEvent` сообщает о тапе и не поглощает событие). Кликабельный слой дерева Compose поверх видео забирал попадание себе: поверхность — настоящий view Android, она видит касание раньше дерева Compose, и кнопки панели до hit-test не доходили (D6). Кликабельный слой над видео в `HistoryVideoPlayer` не ставить.

8. Позиция и гладкость (измерено `dumpsys gfxinfo` на SM-S901E, 120 Гц, 15 с архива, панель Timeline). `HistoryPlayerController` держит `core` (состояние плеера) и позицию в **двух отдельных snapshot-state**: наружу `playback` и `positionSeconds`. Позиция опрашивается раз в секунду (`POSITION_POLL_MILLIS = 1_000L`), состояние панели управления — `CONTROLS_POLL_MILLIS = 250L`. Потребители получают позицию лямбдой `position: () -> Double` и читают её в фазе layout/draw, а не в композиции, поэтому тик позиции не перекомпонует панель. Строки шкалы рисуются **плоским цветом** (alert `HistoryColors.alert` 20%, detection 20%, без записи — чёрный): градиент PWA сознательно отброшен по решению владельца (43% «рваных» кадров с градиентом, 17% без него). `LazyColumn` шкалы вынесен в `CompositingStrategy.Offscreen` (6,7%); курсор двигается смещением в фазе layout, подпись времени — лист `CursorClock`. Итог 5,6% «рваных» кадров против 43% до правок. Перетаскивание курсора считает позицию от базы жеста: `historyScrubPosition(base, dragPx, rowHeightPx, step) = base - dragPx / rowHeightPx * step` (раньше накопленный сдвиг добавлялся к уже сдвинутой позиции, и курсор «прыгал»). Не возвращать градиент и не читать позицию в композиции панели.

Запросы архива ограничены: `GET /api/review` архива берётся с `HISTORY_REVIEW_LIMIT = 2_000` (у сервера нет дефолтного лимита), а тело любого ответа читается через `readBoundedBody` с пределом `MAX_RESPONSE_BODY_CHARS = 16 MiB`. Отменённая загрузка не должна превращаться в ошибку панели: `historyArchiveFailure` пробрасывает `CancellationException`.

Точка входа в архив — только тап по карточке карусели. Если непросмотренных alert за последний час нет, экран недостижим из UI. В PWA запись открывается из списка событий; добавление такого входа в приложении — отдельная задача.

### Безопасность и хранение сессии

- `INTERNET` и `ACCESS_NETWORK_STATE` объявлены в `AndroidManifest.xml`;
- логин и пароль хранятся зашифрованными через AES-GCM ключ Android Keystore;
- session cookie хранится в server-scoped encrypted store;
- credentials запрещены внутри URL;
- TLS verification нельзя отключать;
- нельзя использовать trust-all SSL, `curl -k` или выводить cookies/passwords в логи;
- данные сессии и credentials не должны пересекать origin разных `ServerProfile`.

Сессия восстанавливается сама, без пароля пользователя. Cookie сервера живёт недолго, и когда транспорт получает 401, приложение не выкидывает пользователя на экран входа: `SessionRenewal` (`app/src/main/java/com/frigate/android/SessionRenewal.kt`) берёт сохранённые credentials и подписывается заново, а `renewSession` собирает новое состояние соединения. Правила:

- credentials удаляются **только** когда сервер отверг сам логин (401 на `POST /api/login`): такой пароль больше не работает;
- любая другая ошибка (сервер недоступен, сеть) credentials не трогает — временный сбой не должен стирать логин пользователя;
- без сохранённых credentials восстановления нет: пользователь входит руками;
- один запрос восстановления за раз: несколько транспортов могут сообщить об истёкшей сессии одновременно.

Тот же путь используется при старте приложения: cookie может оказаться просроченной, и приложение подписывается заново по сохранённым credentials, не спрашивая пароль. `onUnauthorized` из `ConnectedApp` ведёт в `renewSessionOf`, а не в `LoginRequired`.

### Переустановка приложения и сохранность настроек

Профиль сервера — обычная preference (`frigate_server`), он включён в Auto Backup через `res/xml/data_extraction_rules.xml`, поэтому переживает переустановку приложения (cloud-backup и device-transfer).

Session cookie (`frigate_secure_sessions`) и credentials (`frigate_secure_credentials`) зашифрованы AES-GCM ключом Android Keystore. Ключ принадлежит приложению и умирает вместе с ним, поэтому их копия в бэкапе — шифротекст, который никто не прочитает; они исключены из бэкапа намеренно. После переустановки нужен один повторный логин.

Поэтому на устройстве пользователя нельзя запускать `connectedAndroidTest`: Gradle удаляет и приложение, и тестовые APK, а вместе с ними — профиль сервера, сессию и credentials. Instrumentation-тесты ставить и запускать так:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  -e class com.frigate.android.feature.live.LiveCameraTileTest \
  com.frigate.android.test/androidx.test.runner.AndroidJUnitRunner
```

`adb install -r` сохраняет данные приложения.

### BlackBox-тесты (UiAutomator)

Каталог `blackbox/` содержит управляемый mock Frigate на Python и раннер `run.sh` ([README](blackbox/README.ru.md)); сами тесты — модуль `:blackbox-tests` (`com.android.test`, `androidx.test.uiautomator` 2.3.0, self-instrumenting) поверх сборки `./gradlew assembleBlackbox`. Она ставится отдельным приложением `com.frigate.android.blackbox` с собственными данными: очистка состояния не затрагивает основное приложение. Запуск читает локальный файл настроек (`blackbox/blackbox.local.env`, в `.gitignore`, права 600); адреса, порты, serial и URL в репозиторий не записываются и значений по умолчанию не имеют. Логин/пароль тест читает у mock по loopback (`/__control/credentials`), не из argv.

Фреймворк выполняет действия пользователя снаружи процесса приложения, включая щипок (`UiObject2.pinchOpen`) и поворот (`UiDevice.setOrientationLeft/Natural`; они замораживают поворот, поэтому тест в `@After` вызывает `unfreezeRotation()`, а раннер не меняет системные настройки сам). Текст в поля вводится напрямую (`UiObject2.text`), без клавиатуры: клавиатура Samsung перекрывала форму. Mock отдаёт реальное видео (режимы звука `/__control/audio/none|sound|silent`, метаданные `/api/go2rtc/streams/<stream>`): `blackbox/mock-server/make_media.py` (PyAV + numpy в отдельном venv, ключ `BB_MEDIA_PYTHON` локального файла настроек) делает H.264 High@4.1 1280×720, пурпурный квадрат движется по зелёному фону, а mock раздаёт его по go2rtc MSE (`/live/mse/api/ws`) и как HLS-архив (`/vod/…`). Поэтому «кадр декодирован» и «воспроизведение идёт» проверяются по скриншотам снаружи процесса (`VideoPlaybackTest`: пурпурный квадрат на экране, его положение меняется). Видео 1280 px нужно ещё и потому, что предел зума считается от ширины источника: на 320 px зума нет. Без `BB_MEDIA_PYTHON` эти тесты пропускаются. Подпись зума зависит от локали («1,0x»), её сравнивают с исходной, а не с литералом. Базовый класс возвращает естественную ориентацию и автоповорот до и после каждого теста. Плитка камеры в сетке: у показанного снимка описание «Yard snapshot», у играющей — крупный кликабельный блок без описания; тесты ищут по обоим признакам.

Mock управляется через `/__control/*`: `review/<epoch>` (фиксированное начало обзора), `reviewed/on|off`, `timezone/<имя>`, `cameras/<n>`, `preview/<video|none>`, отказы `fail/<viewed|preview-media|thumb>/<код>/<раз>`, удержание ответа `hold/<preview-media|motion>/on|off`, `activity` и `ws/send` (push по WebSocket), `audio/<режим>`, журнал запросов `log` (без query, без `/__control/*`, до 500 записей). Тесты считают запросы по этому журналу (`requestCount`, `awaitRequests`). Декодер видео виден снаружи процесса приложения по строке `PID[<pid>]: Current Codec Usage: { VideoDec[ HW: n ] }` в `dumpsys media.resource_manager`: она есть, только пока декодер занят (`videoDecoders()`, `awaitNoDecoders()`). Состояние accessibility Compose (`stateDescription`) через UiAutomation не видно, поэтому состояние карточки (например, зелёный бейдж просмотренного) читается по цвету пикселей. Подпись скорости Motion — отдельный узел рядом с кнопкой «Motion speed».

Статус 2026-11-03, SM-S901E, mock Frigate: 33 теста — вход; продление сессии; карусель → History → Back; Live: открытие/Back, fullscreen и вкладки, поворот, щипок, activity socket при смене вкладок и push, пришедший пока Live скрыт, снимки всех карточек до прокрутки; видео: кадр и 40 с воспроизведения в Live, воспроизведение после поворота, воспроизведение архива History, зум щипком в Live и в History, картинка в картинке с возвратом; звук: поток без звука не даёт кнопки, поток со звуком играет только после нажатия и виден как плеер приложения в `dumpsys audio`, поток с объявленным, но не приходящим звуком выключает дорожку и говорит «This camera has no audio»; Events: отметка просмотренным ровно один раз и возврат из History, повтор после неудачной отметки, часовой пояс интерфейса из конфига, «Show reviewed», превью карточки («Preview unavailable» без записи, 401 на миниатюре и на медиа — одно продление сессии без экрана входа, миниатюра держится до первого декодированного кадра, ползунок идёт после перемотки, закрытие превью освобождает декодер), сессия (фон закрывает встроенное превью, скрытая вкладка и остановленное приложение не опрашивают сервер, фильтр «Show reviewed» сохраняется), Motion (скользящее обновление не роняет камеру, выход из Motion сохраняет скорость, «Motion only» и время, две камеры — два декодера, общая перемотка, ход при воспроизведении и освобождение декодеров). Наблюдение, не проверенное на соответствие PWA: в альбомной ориентации окно камеры показывает полосу картинки около 520 px высотой, и движущийся квадрат выходит за её пределы. Проверка ограничена видимыми элементами и декодированием на синтетическом потоке; многочасовой sustained playback реальных камер и H.265 не проверены. Условие: экран включён и разблокирован до старта; во время долгих тестов тест сам удерживает экран (`keepScreenOn()`), потому что через ~30 с без касаний экран гаснет и воспроизведение останавливается.

## Политика камеры

Текущий dashboard использует `feature/live/LiveTilePolicy.kt`: activity + visibility, current-output readiness, 500 ms grace и snapshot polling. `LiveCameraPolicy.kt` сохраняет прежние scroll helpers для regression tests; recording permission не является playback gate. Не добавлять continuous playback или Wi-Fi routing без отдельной задачи.

## Методика тестирования

Подробная стратегия, выбранный стек и acceptance criteria: [docs/testing-strategy.md](docs/testing-strategy.md).

Краткое правило: native MSE codec negotiation, DataSource/byte queue и buffer policy проверяются JVM unit + transport integration; первый декодированный кадр, sustained playback и release при уходе с Live требуют физического Android device E2E. Для архива History правило то же: JVM-тесты покрывают модель источника, inpoint, диапазон, подпись скорости и построение шкалы, но первый декодированный HLS-кадр, sustained playback и меню скоростей подтверждаются только на устройстве. Компиляция, snapshot и unit-тесты не доказывают runtime playback.

Используется TDD для исправлений поведения:

1. Сначала написать минимальный regression test, который воспроизводит баг.
2. Запустить тест и зафиксировать RED из правильной причины.
3. Внести минимальное production-изменение.
4. Повторно запустить тест и зафиксировать GREEN.
5. Запустить полный unit suite.
6. Собрать debug APK.
7. Для transport/playback/lifecycle дополнительно выполнить runtime smoke на физическом Android-устройстве.

Нельзя считать исправление подтверждённым только по компиляции или unit-тестам, если проблема относится к WebView, WebSocket, MediaSource или lifecycle устройства.

### Unit-тесты

JVM-тесты используют JUnit 5/Jupiter и запускаются через JUnit Platform. Зависимости JUnit имеют только test scope; AndroidX/Compose instrumentation по стратегии остаётся на JUnit 4 и не определяет framework JVM-слоя.

Тесты находятся в `app/src/test/java` и покрывают:

- нормализацию и валидацию `ServerProfile`;
- REST JSON parsing для cameras/profile/events;
- endpoint construction для HTTP/WebSocket/MSE;
- encrypted credentials/session cookie stores;
- camera visibility и порядок Compose callbacks;
- camera activity parsing;
- recording/object indicators;
- legacy `LiveCameraPolicy` и camera playback policy;
- go2rtc MSE codec negotiation/protocol, DataSource read/close/error handling, binary frame order и bounded queue;
- native MSE buffer/catch-up policy;
- legacy MSE HTML/JavaScript invariants (не являются проверкой текущего native player);
- карусель review: фильтр непросмотренных alert, разбор обновлений темы `reviews`, каденс обновления, состояние карусели, загрузка авторизованных превью, сессия просмотра;
- архив History: построение источника и inpoint, диапазон и сетка старта, сетка chunk по часу UTC (тест принудительно меняет зону JVM на `Asia/Kolkata`, иначе старая реализация прошла бы его на машине с часами по UTC), решение о переходе в следующий chunk по окончании записи, модель шкалы и сегментов, панели и фильтр, подпись скорости и список скоростей PWA, HLS data source и navigation;
- манифест `MainActivity`: activity обязана обрабатывать изменение конфигурации сама (`MainActivityManifestTest`);
- зум плеера: предел по разрешению источника, клампы масштаба, зум вокруг точки, границы смещения, pan при 1×, сброс;
- список движков панели: два варианта PWA, доступный MSE, недоступный WebRTC с причиной, MJPEG не движок (`LiveEngineOptionsTest`);
- восстановление сессии: продление по сохранённым credentials, отказ при отвергнутом логине, сохранение credentials при недоступном сервере, вход по HTTP на mock-сервере.

Instrumentation-тесты (`app/src/androidTest`, JUnit 4) — слой Compose-компонентов: поведение плитки (`feature/live/LiveCameraTileTest`), панель камеры и её зум (`feature/live/LiveCameraPanelTest`, `feature/player/PlayerZoomGestureTest`), экран Events, его жесты, мутации и область движения (`feature/events/EventsScreenTest`, `EventsGesturesTest`, `EventsMutationUiTest`, `EventsMotionUiTest`, `MotionRegionUiTest`). Они получают состояние презентации и не доказывают ни транспорт, ни декодирование. Сквозные сценарии на устройстве (вход, продление сессии, навигация Live/Events/History, видео, звук, картинка в картинке, щипок, поворот, превью и Motion в Events) выполняет UiAutomator из `:blackbox-tests`. Компонентные тесты запускать на устройстве пользователя только через `adb install -r` и `adb shell am instrument`: `connectedAndroidTest` удаляет приложение вместе с его данными (см. «Переустановка приложения и сохранность настроек»).

Команды из корня проекта:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest
```

Таргетированный тест:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest \
  --tests com.frigate.android.feature.live.MsePlaybackHtmlTest
```

### Сборка

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

В текущем окружении Java 21 используется явно: Java 25 ранее приводила к конфликту Gradle toolchain/compiler.

### CI (GitHub Actions)

`.github/workflows/ci.yml` запускается на каждый push в `main`, в ветки `ci/*` (один уровень после `ci/`), на теги `v*`, на каждый pull request и вручную через `workflow_dispatch`. Два шага: `testDebugUnitTest assembleDebug`, затем `assembleDebugAndroidTest assembleBlackbox`. Instrumentation- и BlackBox-тесты в CI не запускаются (нужно устройство), но обязаны компилироваться. При падении выкладывается артефакт `test-reports`.

Тестовая сборка из `main`: после зелёного job `build` на push в `main` job `publish-test-apk` собирает debug APK и публикует его GitHub pre-release `test-<номер запуска>` (файл `frigate-android-test-<номер>-<sha7>.apk`). `versionName` получает суффикс `-test.<номер>+<sha7>` через `-PappVersionSuffix`; `versionCode` остаётся 1, иначе локальный `adb install -r` упирался бы в запрет понижения версии. APK подписывается debug-ключом из секрета репозитория `DEBUG_KEYSTORE_BASE64` (base64 от `~/.android/debug.keystore`): на раннере ключ каждый раз новый, а Android не ставит APK с другой подписью поверх установленного, и удаление приложения стёрло бы профиль сервера и сессию. Без секрета job падает с явной ошибкой. Репозиторий публичный, поэтому pre-release виден всем; в APK нет credentials, ключ в репозиторий не записывается. Запуск на `main` не отменяется следующим push (`cancel-in-progress` выключен только для `main`), чтобы не оборвать публикацию.

`./gradlew` — стандартный Gradle wrapper (`gradle/wrapper/`). Версия Gradle (`9.8.2`) и контрольная сумма дистрибутива заданы в `gradle/wrapper/gradle-wrapper.properties`. CI вызывает `./gradlew`, а `gradle/actions/setup-gradle` проверяет контрольную сумму `gradle-wrapper.jar`. JDK в CI — Temurin 21.

Обновление Gradle:

```bash
./gradlew wrapper --gradle-version <версия> --distribution-type bin \
  --gradle-distribution-sha256-sum <sha256 из https://services.gradle.org/distributions/gradle-<версия>-bin.zip.sha256>
```

### Runtime smoke на ADB

При наличии нескольких ADB targets всегда указывать serial через `adb -s <serial>`.

Минимальный сценарий:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.frigate.android/.MainActivity
adb -s <serial> shell dumpsys window
adb -s <serial> exec-out screencap -p > "$CLAUDE_JOB_DIR/tmp/frigate-runtime.png"
```

Проверять нужно не только launch, но и реальное поведение:

- Connected state и сохранённая сессия;
- начальные `latest.webp` snapshots, загрузка Events и runtime camera activity/recording/object indicators;
- activity видимой камеры → still сохраняется до первого кадра текущего native MSE output;
- sustained playback и отсутствие повторного `BUFFERING` на тестовом потоке;
- уход в Events/Settings освобождает media, но сохраняет activity subscription; ON_STOP останавливает оба транспорта;
- корректность поведения при screen off/on и Activity pause/resume.

Логи процесса нужно фильтровать по текущему PID, например:

```bash
PID=$(adb -s <serial> shell pidof com.frigate.android | tr -d '\r')
adb -s <serial> logcat -d -v threadtime --pid="$PID" \
  | grep -E 'FrigateNativeMSE|CameraActivity|FATAL EXCEPTION'
```

Не очищать logcat без явного разрешения. Старые строки logcat нельзя выдавать за доказательство текущего запуска; runtime-вывод должен быть привязан к свежему PID/времени.

Тест поворота обязан запрашивать ориентацию, которой у activity ещё нет: запрос той же ориентации не меняет конфигурацию, activity не пересоздаётся, и тест проходит, ничего не доказывая. Текущая ориентация читается из `resources.configuration.orientation`, а `ActivityInfo.SCREEN_ORIENTATION_*` и `Configuration.ORIENTATION_*` — разные константы, их нельзя сравнивать напрямую.

## Текущие ограничения и известное состояние

- Wi-Fi-specific server routing ещё не реализован.
- Текущий live UI path — native go2rtc MSE через Media3, video-only, без WebView; WebRTC в приложении нет (в списке движков он только приглушён).
- Prototype acceptance на 2026-10-28: на тестовом Samsung SM-S901E `<device-serial>`, камера `back_door1`, получен декодированный кадр и после 2 s catch-up deadband не было повторных `BUFFERING` в runtime sample 19:01:49–19:04:16. Это подтверждает прототип на проверенном потоке, но не широкую совместимость всех камер/кодеков.
- Для runtime-проверки устройства подтвердить `mWakefulness=Awake`, фокус `MainActivity`, свежий PID и непустой screenshot; logcat не очищать.
- При MSE-regression сопоставлять Media3 playback state/position с WebSocket/DataSource delivery; отсутствие transport-событий без отдельного telemetry не доказывает обрыв. В production не логировать cookies, credentials или media payload.
- Экран архива History открывается только тапом по карточке карусели непросмотренных alert. Если таких alert за последний час нет, экран недостижим из UI: в PWA запись открывается из списка событий, а в разделе Events приложения такого входа пока нет. Расширение входа — отдельная задача.
- Архив проверен на устройстве на потоке H.264 одной камеры (`street2`); многочасовые диапазоны, H.265, wide-компоновка от 600 dp и Compose E2E не проверялись.
- Переход в следующий часовой chunk подтверждён на устройстве 2026-10-30 (playhead перешёл границу 17:00 и OSD камеры шёл без разрыва). Предельный случай: если в следующем chunk у камеры нет записи, playback останавливается штатно, без сообщения об ошибке.
- `adb shell uiautomator dump` между двумя тапами поглощает следующий тап и даёт ложный диагноз «кнопка не реагирует». Для последовательных тапов использовать `screencap` без dump.
- Верификация карусели и History: [docs/live-review-history-verification.md](docs/live-review-history-verification.md). Пайрет PWA сверялся с upstream checkout, а не с развёрнутой у пользователя версией — совпадение версий не установлено.
- Поворот экрана проверен на устройстве: без `android:configChanges` activity пересоздавалась и открытая камера терялась; с фиксом activity переживает поворот, камера остаётся открытой, а видео идёт после поворота (BlackBox `livePlaybackSurvivesTheRotation`). Архив History через тот же механизм: его навигация живёт в той же композиции; поворот в архиве не проверен отдельно.
- Панель live-камеры и зум плеера реализованы 2026-11-01. После замечания владельца шапка исправлена по утверждённому макету: контуры, размеры, заливки, интервалы, снимок в широкой шапке, отсутствие шестерёнки в fullscreen. Состав шапки и настроек доведён до возможностей камеры: звук по metadata потока, картинка в картинке, ручная запись и admin-тумблеры по роли, выбор потока, список движков (MSE и приглушённый WebRTC с причиной), статистика и отладочный вид; список движков и ручная запись в шапке — по итогам сверки с PWA. Свежие проверки: unit suite 387 тестов PASS, debug build успешен, 35 проверок HTML-макета PASS, полный instrumentation-набор на устройстве PASS; обычная и fullscreen-шапки осмотрены на устройстве. Зум подтверждён владельцем. Не проверены вручную: сохранение кадра в галерею, свайп листа настроек; отдельного wide-устройства не было. Нижняя навигация приложения и системные панели в fullscreen пока остаются — immersive Android fullscreen не реализован.
- Сессия на устройстве пользователя истекла сама 2026-10-31 (сервер отверг cookie, ~24 ч жизни сессии). Приложение выкинуло пользователя на экран входа вместо повторного входа по сохранённым credentials. Причина исправлена 2026-11-01 (`SessionRenewal`): теперь при 401 приложение подписывается заново само, а credentials удаляются только при отвергнутом логине. Проверено на устройстве: после перезапуска приложение вошло само, и BlackBox `SessionRenewTest` проходит (расшифровка Keystore + повторный вход на mock).

## Правила изменений

- Сначала читать файл и соседние тесты, затем редактировать.
- Для bug fix сначала RED-тест, затем минимальный GREEN-fix.
- Не смешивать несколько гипотез в одном изменении.
- Не удалять и не перезаписывать пользовательские данные или настройки устройства без явного разрешения.
- Не публиковать credentials, session cookies, PIN, URL с секретами или чувствительные runtime-данные.
- Перед заявлением «исправлено» запускать свежую проверку, соответствующую утверждению: unit test, full suite, build или runtime evidence.
- Сохранять стиль существующего Kotlin/Compose-кода и не добавлять библиотеку, если текущий стек уже решает задачу.
