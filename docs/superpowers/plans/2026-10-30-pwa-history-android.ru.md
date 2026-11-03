**[English](2026-10-30-pwa-history-android.md)** | Русский

# Live Review Carousel and PWA History — Native Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Воспроизвести в native Android принятые макеты: карусель непросмотренных alerts на Live и экран History по образцу Frigate PWA.

**Architecture:** Review segment — единый контракт для карусели и History (`GET /api/review`, `POST /api/reviews/viewed`). Список карусели живёт в session-scoped контроллере, который инвалидируется существующим shared `/ws` через topic `reviews` до дедупликации camera reducer — второй сокет не нужен. History — отдельный экран с собственным Media3 HLS-плеером поверх nginx-vod (`{base}/vod/{camera}/start/{after}/end/{before}/master.m3u8`) с hour-chunk окном, повторяющим PWA. Native go2rtc MSE Live не меняется.

**Tech Stack:** Kotlin 2.2.10, Android 12+, Compose BOM 2024.12.01, Coroutines, Gson 2.11.0, OkHttp 4.12.0, Media3 1.9.0 (`exoplayer`, `exoplayer-hls`, `datasource-okhttp`), JUnit 5/Jupiter.

**Spec:** `docs/superpowers/specs/2026-10-30-pwa-history-android-design.md`. Визуальные макеты: `docs/design/live-unreviewed-carousel.html`, `docs/design/live-pwa-history.html`. Основание правок макета: `docs/design/live-pwa-history-sources.md`.

**Источник истины по контрактам:** Frigate upstream checkout `<upstream-checkout>/frigate` (только чтение, не запускался):
`frigate/api/review.py`, `frigate/api/record.py`, `frigate/api/defs/query/review_query_parameters.py`, `frigate/api/defs/response/review_response.py`, `frigate/models.py`, `frigate/review/maintainer.py`, `docker/main/rootfs/usr/local/nginx/conf/nginx.conf`, `web/src/views/recording/RecordingView.tsx`, `web/src/views/live/LiveDashboardView.tsx`, `web/src/components/player/dynamic/DynamicVideoPlayer.tsx`, `web/src/utils/timelineUtil.tsx`, `web/src/types/review.ts`.

## Global Constraints

- Работать in-place, без commits/worktree. Не удалять данные/настройки устройства, не чистить logcat.
- Перед edit прочитать файл и соседние тесты; перед изменением функции найти всех callers.
- TDD: targeted RED по правильной причине → минимальный production change → GREEN → полный suite → debug APK. Не смешивать гипотезы.
- Не читать/искать `build`, `node_modules`, `.git`, `dist`, `__pycache__`. Не публиковать credentials/cookies/приватные кадры.
- URL строятся относительно `ServerProfile` с сохранением deployment prefix. Cookie — только заголовком своего origin. Redirects запрещены. TLS verification включена. Credentials в URL запрещены.
- `/api/review`: `cameras` (csv или `all`), `labels`, `zones`, `reviewed` (0/1), `limit`, `severity`, `before`, `after` (float Unix seconds). Ответ — массив объектов: `id`, `camera`, `start_time` (float Unix seconds), `end_time` (float или null у ongoing), `severity` (`alert`/`detection`/`significant_motion`), `thumb_path`, `has_been_reviewed` (bool), `data` (`objects`, `zones`, `audio`, `detections`, `sub_labels` — массивы строк).
- `thumb_path` вида `/media/frigate/clips/review/thumb-{camera}-{id}.webp` → profile-relative `{base}/clips/review/thumb-{camera}-{id}.webp`. Только этот префикс; absolute URL, traversal и неизвестные пути → fallback без сетевого запроса.
- `POST /api/reviews/viewed` body `{"ids":[...],"reviewed":true}`.
- VOD плейлист: `{base}/vod/{camera}/start/{after}/end/{before}/master.m3u8` (nginx-vod, `vod hls`, fMP4, `vod_hls_version 6`). Окно — hour-chunk, содержащий playhead, как в PWA `getChunkedTimeDay`/`findChunkIndex`. Fallback позиции вне диапазона — `rangeEnd - 60`.
- Archive endpoints: `GET /api/{camera}/recordings/coverage?before=&after=&timelines=` → `{spans:[{start_time,end_time,streams[]}], codecs_compatible, streams}`; `GET /api/review/activity/motion?cameras=&before=&after=&scale=` → `[{start_time,motion,camera}]`; `GET /api/recordings/unavailable?cameras=&before=&after=&scale=` → `[{camera,start_time,end_time}]`.
- Touch targets ≥ 48 dp, content description на иконочных кнопках, цвет не единственный признак состояния.
- Production без новой library family: добавить только `media3-exoplayer-hls` и `media3-datasource-okhttp` версии 1.9.0.
- Unit/build не доказывают playback: первый декодированный кадр, sustained playback и release при уходе с History проверяются на физическом устройстве.
- Viewed writes меняют пользовательский статус на сервере: на device-smoke только для явно разрешённого segment, иначе мутация проверяется на mock transport.

## Review Focus

1. HLS-манифест/сегмент/ключ с чужим origin или с redirect: отклонить **до** сетевого запроса и не передавать cookie чужому origin (два тестовых origin в одном тесте).
2. GET списка review, начатый до успешного `POST /reviews/viewed`, завершившийся позже: отмеченная карточка не возвращается в ленту.
3. Переход камеры в History сохраняет wall-clock позицию, а не позицию относительно события; смена диапазона/масштаба шкалы меняет identity плеера.
4. Ongoing-событие (null `end_time`): конечный валидный VOD-диапазон, без бесконечного retry; при достижении конца диапазона — понятная остановка.
5. Item карусели не должен попадать в camera visibility как камера: скролл карусели не запускает live-камеру, а её key отличается от camera ID.

---

### Task 1: Review contract — модель, парсинг, REST, безопасный thumbnail URL

**Files:**
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateModels.kt`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateApiParser.kt`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateClient.kt`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateRepository.kt`
- Create: `app/src/main/java/com/frigate/android/core/server/ReviewMediaEndpoint.kt`
- Test: `app/src/test/java/com/frigate/android/core/server/FrigateApiParserTest.kt` (modify)
- Test: `app/src/test/java/com/frigate/android/core/server/FrigateClientTest.kt` (modify)
- Test: `app/src/test/java/com/frigate/android/core/server/ReviewMediaEndpointTest.kt` (create)

Пути production здесь и ниже относительно `app/src/main/java/com/frigate/android/`.

**Interfaces:**
- `data class FrigateReview(id: String, camera: String, startTime: Double, endTime: Double?, severity: String, hasBeenReviewed: Boolean, thumbnailPath: String?, objectLabels: List<String>)`
- `FrigateApiParser.parseReviews(body: String): List<FrigateReview>` — `objectLabels` = `data.objects`; элемент без `id`/`camera`/`start_time` пропускается.
- `FrigateClient.reviewQuery(cameraIds: List<String>, severity: String? = null, reviewed: Int? = null, limit: Int? = null, before: Double? = null, after: Double? = null): String` — собирает query-строку без credentials.
- `FrigateClient.reviews(cameraIds: List<String>, severity: String? = null, reviewed: Int? = null, limit: Int? = null, before: Double? = null, after: Double? = null): List<FrigateReview>`
- `FrigateClient.markReviewsViewed(ids: List<String>, reviewed: Boolean = true): Unit`
- `FrigateRepository.loadReviews(cameraIds: List<String>, severity: String? = null, reviewed: Int? = null, limit: Int? = null, before: Double? = null, after: Double? = null): List<FrigateReview>`, `markReviewsViewed(ids: List<String>, reviewed: Boolean = true)`.
- `ReviewMediaEndpoint.thumbnailUrl(profile: ServerProfile, thumbnailPath: String?): String?` — только `/media/frigate/clips/review/…` → `{base}/clips/review/…`; иначе null.
- `ReviewMediaEndpoint.vodPlaylistUrl(profile: ServerProfile, camera: String, after: Double, before: Double): String` — `{base}/vod/{encoded camera}/start/{after}/end/{before}/master.m3u8`; `after`/`before` — locale-independent числа (без локали, без экспоненты).

- [x] **RED:** в `FrigateApiParserTest` — разбор реального JSON `/api/review`: `start_time`/`end_time` как Unix seconds Double; `end_time: null` у ongoing; `thumb_path`; `data.objects` → `objectLabels`; `has_been_reviewed`; `severity`. Битые обязательные поля не создают выдуманные id/времена.
- [x] **RED:** в `FrigateClientTest` (MockWebServer) — GET `api/review` с `cameras=a,b&severity=alert&reviewed=0&limit=10`; POST `api/reviews/viewed` body `{"ids":["x"],"reviewed":true}`; заголовок `Cookie`; deployment prefix сохранён; 401/403 → `FrigateHttpException`; redirect не follow (код 302 без follow). Пустой `cameraIds` → пустой список без запроса.
- [x] **RED:** в `ReviewMediaEndpointTest` — `/media/frigate/clips/review/thumb-cam-1.webp` → `{base}/clips/review/thumb-cam-1.webp`; absolute URL, protocol-relative `//host/x`, `..` traversal, encoded `%2e%2e`, другой prefix (`/media/frigate/clips/export/…`), пустой и null → null; `vodPlaylistUrl` кодирует camera и формирует `vod/…/master.m3u8` без `api/`.
- [x] Запустить targeted-тесты, зафиксировать RED по правильной причине.
- [x] Реализовать через существующий `FrigateClient.request` и Gson; не дублировать login/session storage; `markReviewsViewed` — POST с JSON body через тот же `request`.
- [x] Повторить targeted suite до GREEN.

### Task 2: Review-состояние и инвалидация через существующий WebSocket

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/review/ReviewUpdates.kt`
- Create: `app/src/main/java/com/frigate/android/feature/review/ReviewController.kt`
- Create: `app/src/main/java/com/frigate/android/feature/review/ReviewSession.kt`
- Modify: `app/src/main/java/com/frigate/android/feature/live/CameraActivityWebSocket.kt`
- Modify: `app/src/main/java/com/frigate/android/feature/live/CameraActivitySession.kt`
- Test: `app/src/test/java/com/frigate/android/feature/review/ReviewUpdatesTest.kt` (create)
- Test: `app/src/test/java/com/frigate/android/feature/review/ReviewControllerTest.kt` (create)

**Interfaces:**
- `reviewRefreshDelayMillis(raw: String): Long?` — decode WS-envelope Frigate + nested payload; `end` → 1000, `new`/`genai` → 6000, рост objects → 5000, иначе null.
- `unreviewedAlerts(items: List<FrigateReview>, cameraIds: Set<String>, nowSeconds: Double, limit: Int = 10): List<FrigateReview>` — только `severity == "alert"`, `!hasBeenReviewed`, `startTime > now - 3600`, distinct id, `startTime` по убыванию, limit.
- `data class ReviewUiState(items: List<FrigateReview>, loading: Boolean, refreshFailed: Boolean, markingIds: Set<String>, markFailedIds: Set<String>)`
- `class ReviewController(repository: FrigateRepository, scope: CoroutineScope, nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 })` — `val state: StateFlow<ReviewUiState>`; `setActive(active: Boolean, cameraIds: Set<String>)`; `invalidate(delayMillis: Long = 0)`; `markViewed(id: String)`; `close()`.
- `rememberReviewSession(profile: ServerProfile, cookie: String, active: Boolean, cameraIds: Set<String>, onUnauthorized: () -> Unit): ReviewSessionHandle` — владеет controller; handle отдаёт `state: StateFlow<ReviewUiState>` и действия `onReviewsMessage`/`onReconnect`/`markViewed`/`refresh`; при смене profile/cookie controller пересоздаётся.

- [x] **RED:** `ReviewUpdatesTest` — synthetic WS payloads new/end/genai/object-growth/malformed; `unreviewedAlerts`: boundary `now-3600` исключается, ongoing с null end сохраняется, alert-only, distinct id, порядок по убыванию, limit 10.
- [x] **RED:** `ReviewControllerTest` — initial/ON_START/reconnect вызывает REST; STOP/скрытие вкладки отменяет работу; повторные invalidations сохраняют ближайший дедлайн; сигнал, пришедший во время GET, не теряется; failed refresh сохраняет items; failed mark сохраняет карточку с retry; successful mark удаляет карточку; два нажатия → один POST; generation прошлой сессии не публикуется.
- [x] **RED:** `CameraActivityWebSocket` — topic `reviews` вызывает review-callback **до** раннего `return` при неизменном camera state; неизменный camera state по-прежнему подавляет camera-обновления (прежние expectations не меняются).
- [x] Реализовать: dispatch review callbacks до reducer equality early-return; reconnect callback публикуется после проверки generation; свежие callbacks — через `rememberUpdatedState`, без пересоздания сокета; один сокет на сессию.
- [x] 401 → callback владельца подключения (существующий flow логина); 403/404/transport → локальная ошибка без сброса camera states.
- [x] Targeted GREEN + регрессия существующих camera reducer/socket тестов.

### Task 3: Авторизованные превью и карусель на Live

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/review/ReviewThumbnailLoader.kt`
- Create: `app/src/main/java/com/frigate/android/feature/review/ReviewCarousel.kt`
- Modify: `app/src/main/java/com/frigate/android/feature/live/LiveCameraScreen.kt`
- Modify: `app/src/main/java/com/frigate/android/MainActivity.kt`
- Test: `app/src/test/java/com/frigate/android/feature/review/ReviewThumbnailLoaderTest.kt` (create)

**Interfaces:**
- `ReviewThumbnailLoader.load(profile: ServerProfile, cookie: String, review: FrigateReview, client: OkHttpClient = defaultClient): ByteArray?` — suspend, cancellable; `null` при неизвестном/невалидном `thumb_path` без сетевого запроса; ответ ≤ 2 MiB; magic-байты проверяются до decode.
- `ReviewCarousel(state: ReviewUiState, profile: ServerProfile, cookie: String, cameraNames: Map<String, String>, onOpen: (FrigateReview) -> Unit, onMarkViewed: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier, nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 })` — чистая логика состояния вынесена в `ReviewCarouselMode`/`reviewCarouselMode`/`reviewTimeAgo`/`reviewObjectSummary` (`ReviewCarouselState.kt`).

- [x] **RED:** loader — Cookie-заголовок, base path с deployment prefix, redirect отклоняется, отмена вызывает `call.cancel()`, non-image → null, oversized → null; неизвестный `thumb_path` → null без запроса.
- [x] **RED:** карусель отдаёт разные callbacks: тап по превью → `onOpen`, тап по кнопке отметки → `onMarkViewed`; появление и scroll не отмечают ничего; пустой список скрывает блок; ошибка списка даёт Retry.
- [x] Реализовать по `docs/design/live-unreviewed-carousel.html`: horizontal row под заголовком Live, только dashboard (не detail камеры, не History); до 10 items, новые слева; имя камеры, относительное время, объекты, признак ongoing, отдельная кнопка отметки ≥48 dp; без autoplay и автопрокрутки.
- [x] Карусель — full-span item существующего `LazyVerticalGrid` с key, отличным от camera ID; item не попадает в `visibleCameraIds`.
- [x] GREEN regression: видимость камеры 49/50 %, сдвиг header, camera identity сохраняет player; пустая лента не оставляет gap; `NativeMsePlayer`/`Go2RtcMseDataSource`/`MseLiveBufferPolicy` не изменены.

### Task 4: History — модель диапазона, chunking, timeline-данные, VOD URL

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryRange.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryTimelineModel.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryDataSource.kt`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateModels.kt` — `MotionPoint`, `UnavailableSpan`, `CoverageSpan`, `TimelineSpan`, `RecordingCoverage`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateApiParser.kt` — `parseMotionActivity`, `parseUnavailableRecordings`, `parseRecordingCoverage`
- Modify: `app/src/main/java/com/frigate/android/core/server/FrigateClient.kt` — `reviewMotion`, `unavailableRecordings`, `recordingCoverage`
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryRangeTest.kt` (create)
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryTimelineModelTest.kt` (create)
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryDataSourceTest.kt` (create)
- Test: `app/src/test/java/com/frigate/android/core/server/FrigateApiParserTest.kt` (modify)
- Test: `app/src/test/java/com/frigate/android/core/server/FrigateClientTest.kt` (modify)

**Interfaces:**
- `data class HistoryRange(val after: Double, val before: Double)`
- `HistoryRange.initial(nowSeconds: Double, hours: Int = 24): HistoryRange` — `after = now - 86400`, `before = now`.
- `HistoryRange.daySeconds(localEpochSeconds: Double, zone: java.time.ZoneId): HistoryRange` — границы выбранного дня.
- `HistoryRange.startPosition(startTime: Double): Double` — `startTime` внутри диапазона, иначе `before - 60`. Отступ `REVIEW_PADDING = 4` применяется вызывающим кодом (позиция = `review.startTime - 4`), как в PWA.
- `chunkIndex(chunks: List<HistoryRange>, timestamp: Double): Int` — half-open `[after, before)`, последний chunk закрытый.
- `chunkedRange(range: HistoryRange, zone: java.time.ZoneId): List<HistoryRange>` — часовые чанки, как `getChunkedTimeDay`.
- `alignStart(timestamp, segmentDuration)` / `alignEnd(timestamp, segmentDuration)` — выравнивание по сетке сегментов.
- `data class HistorySegment(val startTime: Double, val endTime: Double, val severity: String?, val reviewed: Boolean, val objectCount: Int, val firstHalfMotion: Double, val secondHalfMotion: Double, val hasRecording: Boolean, val subOnly: Boolean)`
- `HistoryTimelineModel.build(camera: String, reviews: List<FrigateReview>, motion: List<MotionPoint>, unavailable: List<UnavailableSpan>, coverage: List<CoverageSpan>, range: HistoryRange, segmentDuration: Double): List<HistorySegment>` — только данные выбранной камеры; сегменты новые сверху, сетка PWA (2 сегмента запаса с каждого края).
- `HistoryTimelineModel.severityRank(severity: String?): Int` — alert 3, detection 2, significant_motion 1.
- `enum class HistoryPanelMode { TIMELINE, EVENTS, DETAIL }`
- `data class TimelineZoom(val segmentDuration: Double, val timestampSpreadMinutes: Int)` и `HISTORY_ZOOM_LEVELS` — 30/15/5 s при 15/5/1 min.
- `data class MotionPoint(val startTime: Double, val motion: Double, val camera: String)`, `UnavailableSpan(camera, startTime, endTime)`, `CoverageSpan(startTime, endTime, streams: List<String>)`, `TimelineSpan(startTime, endTime, durationMs)`, `RecordingCoverage(spans, timelines)` — в `core/server`, чтобы парсер не зависел от feature-слоя.
- `HistoryDataSource(client: FrigateClient)` — `reviews`, `motion`, `unavailable`, `coverage`; окно выравнивается по сетке сегментов, motion запрашивается с `scale = segmentDuration / 2`, recording gaps — с `scale = segmentDuration`.

- [x] **RED:** `HistoryRangeTest` — initial range; `startPosition` внутри/вне диапазона (fallback `before - 60`); `chunkedRange` даёт часовые чанки, покрывающие диапазон, последний закрыт; `chunkIndex` на границах; календарный день по `ZoneId`.
- [x] **RED:** `HistoryTimelineModelTest` — сегменты по severity (alert красный, detection оранжевый, reviewed приглушён), motion-линии симметричны относительно оси, пробелы записи (unavailable) чёрные с границами, шаг сегмента 30/15/5 s при масштабах 15/5/1 min, новые сверху.
- [x] Реализовать чистые функции без сети; источник данных — `HistoryDataSource` поверх `FrigateClient`.
- [x] Targeted GREEN.

### Task 5: Транспорт записи — HLS с origin-защитой

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryHlsDataSourceFactory.kt`
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryHlsDataSourceFactoryTest.kt` (create)

**Interfaces:**
- `HistoryHlsDataSourceFactory.create(profile: ServerProfile, cookie: String): DataSource.Factory` — OkHttp-backed factory; каждый `DataSpec` проверяется до open: только http/https, тот же scheme/host/effective-port, что и `profile.baseUrl`; `file`/`asset`/`content` — отклоняются; redirect запрещён (`followRedirects(false)`); заголовок `Cookie` ставится только для своего origin и не переопределяется DataSpec.
- `HistoryMediaTransport(origin: URI, cookie: String)` — Uri-free логика origin-проверки, доступная JVM-тестам: `isSameOrigin(candidate: URI): Boolean` (scheme/host/effective-port), `checkUrl(url: String): URI` (бросает `HistoryMediaException`), `headers(supplied: Map<String,String>): Map<String,String>` (session cookie всегда перекрывает переданный `Cookie`), `request(url: String): okhttp3.Request`, `client: OkHttpClient` (`followRedirects(false)`).
- `HistoryMediaDataSource(delegate: DataSource, transport: HistoryMediaTransport)` — тонкий Media3-адаптер поверх `OkHttpDataSource`: `open()` проверяет origin до обращения и подставляет заголовки транспорта.

**Замечание по тестируемости:** `android.net.Uri` в JVM unit-тестах — стаб (`RuntimeException`), поэтому `DataSpec`/`Uri` в JVM не собираются. Проверки origin и cookie живут в `HistoryMediaTransport` (без `android.*`) и покрыты тестом; Media3-адаптер — тонкая обёртка над ними.

- [x] Добавить только `androidx-media3-exoplayer-hls` и `androidx-media3-datasource-okhttp` с общей `version.ref media3 = 1.9.0`.
- [x] **RED:** synthetic master → child playlist → init/map → segment/key: каждый запрос получает `Cookie` своего origin; манифест с чужим origin отклоняется **до** обращения к чужому MockWebServer (счётчик запросов второго сервера остаётся 0); redirect на другой host и на тот же host отклоняются без follow; `file:///`, `content://`, `asset://` из манифеста отклоняются; DataSpec-заголовок не может подменить session cookie.
- [x] Реализовать factory; один factory используется и player, и тесты.
- [x] Targeted GREEN. Совместимость API Media3 подтвердить компиляцией pinned 1.9.0 (docs могут быть новее).

### Task 6: Экран History — раскладка, видео, управление, панели

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryScreen.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryVideoPlayer.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryTimelinePanel.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryEventsPanel.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryDetailPanel.kt`
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryScreenModel.kt`
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryPlayerStateTest.kt` (create)
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryScreenModelTest.kt` (create)

**Interfaces:**
- `data class HistoryPlayerState(val positionSeconds: Double, val playing: Boolean, val muted: Boolean, val speed: Float, val controlsVisible: Boolean, val loading: Boolean, val error: HistoryError?)`
- `sealed class HistoryError { object NoRecording; object Forbidden; object Unauthorized; data class Network(val message: String); data class Codec(val message: String) }`
- `HistoryPlayerController` — позиция, play/pause, ±10 s, mute, скорость 0.5/1/2/4/8/16, автоскрытие controls через 4 s; перемотка только через шкалу архива; собственного clip-slider нет.
- `HistoryScreenModel(anchorSeconds, bounds)` — wall-clock playhead (`anchorSeconds`), `zoomIndex`/`zoom`, `seekToken`; `seekTo`, `zoomIn`, `zoomOut`; смена камеры не трогает anchor, поэтому момент суток сохраняется.
- `HistoryScreen(profile, cookie, camera, cameraName, panelMode, initialReview, range, onBack, onLive, onOpenCameraPicker, onOpenPanelPicker, onOpenSettings, onUnauthorized)` — камера и режим панели поднимаются к владельцу экрана (Task 7/8), playhead остаётся внутри `HistoryScreen`. (Task 7: `onOpen*Picker` заменены на `onCameraChange/onPanelModeChange/onRangeChange/onFilterChange`, экран владеет drawer'ами.)

**Реализовано (Task 6):**
- Шкала таймлайна строится по **всему** диапазону (как в PWA), hour-chunk — только окно VOD-плейлиста; поэтому перетаскивание курсора может увести playhead в соседний час, и chunk/плейлист пересобираются.
- Архив камеры грузится по всему диапазону одним набором из четырёх запросов (reviews/motion/unavailable/coverage); плейлист открывается только после готового coverage — seek игрока считается по realized timelines и не «прыгает» на первых кадрах.
- Слушатель плеера регистрируется на поколение источника и снимается до следующего: late-callback прошлой generation не меняет UI.
- `HistoryScreenModel` держит wall-clock playhead, масштаб шкалы и счётчик seek; камера и режим панели подняты к владельцу экрана (Task 7/8), поэтому смена камеры сохраняет момент суток.


- [x] **RED:** `HistoryPlayerStateTest` — чистые переходы состояния: play/pause, ±10 s с клампингом к диапазону, mute, скорость, автоскрытие controls по 4 s, тап по кадру не переключает воспроизведение, ошибки 404/403/401/сеть различаются, у каждой ошибки есть Retry.
- [x] Реализовать раскладку: portrait — header 48 dp (Back + Live слева, логотип по центру, камера/панель/настройки справа), видео `Fit` в чёрной подложке со скруглёнными углами ≤50 % высоты, панель ниже; landscape — видео слева, панель справа (Timeline 100 dp, Events/Detail 304 dp).
- [x] Реализовать панели по макету: Timeline — вертикальная шкала, новое сверху, шаг 30/15/5 s при масштабах 15/5/1 min, строки 8 dp, симметричные motion-линии 2 dp, severity-заливка, пробелы записи, красная линия позиции и центральная плашка 80 dp с перетаскиванием; шкала следует за позицией, пока пользователь не взаимодействовал; `+`/`−` масштаба в правом нижнем углу. Events — все review items камеры за диапазон, тап перематывает `start - 4` и запускает воспроизведение. Detail — раскрываемые группы review items (время, severity, число объектов, длительность), внутри lifecycle объекта с точным seek, внизу «Настройки подробного просмотра» (смещение аннотаций, «Всегда раскрывать активное»).
- [x] Реализовать панель управления поверх видео: порядок кнопок — звук, −10 s, play/pause, +10 s, скорость, снимок кадра, fullscreen; порядок и набор строго по макету; звук по умолчанию выключен.
- [x] Плеер: отдельный `ExoPlayer` + `HlsMediaSource` с factory Task 5; источник пересобирается при смене camera/chunk/range/retry (identity); late-callback прошлой generation не меняет UI; ошибки → состояние с Retry, без вечного spinner.

### Task 7: Drawers, календарь и фильтр History

**Files:**
- Create: `app/src/main/java/com/frigate/android/feature/history/HistoryDrawers.kt`
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryFilterTest.kt` (create)

**Interfaces:**
- `data class HistoryFilter(val labels: Set<String>?, val zones: Set<String>?, val severities: Set<String>)`
- `HistoryFilter.apply(current: HistoryFilter, draft: HistoryFilter): HistoryFilter` — нельзя снять последнюю явно выбранную метку.
- `HistoryFilter.reset(): HistoryFilter` — `labels = null` (все метки), `zones = null`, `severities = all`.

**Реализовано (Task 7):**
- Drawers — `ModalBottomSheet` из Material3 (`HistoryDrawers.kt`): выбор камеры (радиогруппа имён, выбор закрывает drawer и поднимает `onCameraChange`; playhead остаётся wall-clock, поэтому момент суток сохраняется), режим панели (Timeline / Events / Detail), меню настроек одной полноширинной колонкой: Export, Calendar, Filter, Share moment, Motion search.
- Страницы настроек открываются **внутри** того же sheet (Menu → Calendar / Filter с кнопкой «назад»), как в PWA, где содержимое sheet заменяется на месте.
- Семантика фильтра взята из принятого макета: `draft.labels == null` — «все метки»; галка «All labels» снимает выборочные метки; снять последнюю явно выбранную метку нельзя (`draft.length > 1`); Reset → `labels = null`; Apply → `reviewLabels = draft`.
- Календарь: Material3 `DatePicker` + «Last 24 hours», который возвращает исходное окно сессии (`initialRange`), а не «последние 24 часа от текущей границы» — календарный день тоже длиной 24 ч, поэтому окно узнаётся по границам, а не по длине.
- Export, Share moment, Motion search открывают честное пояснение и ничего не выполняют — ровно как в макете.
- Фильтр применяется к событиям панелей Events/Detail и к отметкам severity на шкале (в PWA `currentCameraItems()` используется и там, и там).
- Список меток для фильтра берётся из реальных review-меток камеры, а не из захардкоженных «Человек/Автомобиль» макета.
- UI-строки — английские, как во всём приложении; `stringResource` в проекте не используется.

**Изменения сигнатуры `HistoryScreen`:** `cameras`, `filter`, `onCameraChange`, `onPanelModeChange`, `onRangeChange`, `onFilterChange` вместо `onOpenCameraPicker/onOpenPanelPicker/onOpenSettings` — экран сам владеет состоянием drawer'ов и поднимает выбор наверх, владелец экрана хранит сессию просмотра (Task 8).

- [x] **RED:** `HistoryFilterTest` — «Все метки» снимает выборочные метки; последнюю выбранную метку снять нельзя; Apply применяет, Reset возвращает исходное; фильтр — метки/зоны/severity, а не выбор камеры.
- [x] Реализовать bottom drawers по макету: выбор камеры (смена сохраняет wall-clock), режим панели (Хронология / События / Деталь, выбор закрывает drawer, режим запоминается в сессии просмотра), меню настроек одной полноширинной колонкой: Экспорт, Календарь, Фильтр, Поделиться моментом, Поиск движения.
- [x] Календарь меняет диапазон на выбранный день; «Последние 24 часа» возвращает исходный. Экспорт, «Поделиться моментом», «Поиск движения» показывают честное пояснение и ничего не выполняют.

### Task 8: Навигация Live ↔ History

**Files:**
- Modify: `app/src/main/java/com/frigate/android/MainActivity.kt`
- Modify: `app/src/main/java/com/frigate/android/feature/live/LiveCameraScreen.kt`
- Test: `app/src/test/java/com/frigate/android/feature/history/HistoryNavigationTest.kt` (create)

**Interfaces:**
- В `ConnectedApp`: `selectedReview: FrigateReview?` отдельно от `selectedCameraId`; `historyCameraId: String?`; `historyRange: HistoryRange`.
- Открытие History из карусели: тап по review → `selectedReview = review`, `historyCameraId = review.camera`, `historyRange = HistoryRange.initial(now)`, позиция = `review.startTime - 4`.
- Live из History открывает Live **выбранной** камеры.
- Back из History → Live с восстановлением позиции сетки; смена вкладки закрывает History.

**Реализовано (Task 8):**
- Сессия просмотра вынесена в чистый `HistoryNavigation` (`HistoryNavigation.kt`): камера, окно, режим панели, фильтр, playhead и состояние плеера живут в ней, а не в composable-состоянии экрана. **Все свойства — `mutableStateOf`**, иначе `navigation.isOpen` никогда бы не вызвал рекомпозицию и архив не открылся бы.
- `anchorSeconds` — это **начало источника** (playlist origin — часовой chunk записи), а не позиция воспроизведения: playhead живёт в `player.state.positionSeconds`. Отсюда `seekTo(seconds)` двигает оба, а `resumePlayback()` перестраивает источник в текущей позиции при повторном открытии. Уточнение по итогам review: окно источника — целый часовой chunk, поэтому перемотка внутри часа не перезапрашивает манифест (см. задачу 9).
- Смена камеры сохраняет момент суток: `changeCamera` не трогает anchor и playhead — меняется только содержимое записи.
- `LazyGridState` поднят в `ConnectedApp` **выше** веток Live/History, поэтому скролл камер переживает архив; выбранная live-камера (`liveCameraId`) — тоже state уровня сессии, а не `remember` внутри `LiveCameraScreen` (иначе Back из архива терял бы выбор).
- Live-ветка при открытии архива размонтируется → `pool.releaseAll()` освобождает native MSE-плееры; shared activity socket остаётся живым (`rememberCameraActivity` — выше веток), как и в прежнем поведении.
- Любая вкладка нижней навигации закрывает архив: Live — домашний экран сессии.
- `markViewed` отправляется в момент открытия архива, независимо от готовности медиа; ошибка отметки показывается красной галочкой в карусели и повторяется тапом по ней, просмотр не блокируется.

- [x] **RED:** `HistoryNavigationTest` — тап по карусели открывает History (не live-камеру); Back возвращает Live и сохраняет позицию сетки; таб закрывает History; shared activity socket остаётся один; Live media освобождается при открытии History.
- [x] **RED→GREEN lifecycle:** уход из History освобождает player и binding; возврат пересоздаёт с сохранённой позицией и play/pause; смена session generation не публикует старые callback'и.
- [x] Grid state (`LazyGridState`) хранится выше detail/History веток, keyed by session, чтобы Back восстанавливал позицию.
- [x] Открытие History отправляет `markViewed` независимо от готовности медиа; ошибка отметки показывает retry и не блокирует просмотр.

### Task 9: Полная верификация и отчёт

**Files:** update `docs/testing-strategy.md`, `CLAUDE.md`; create `docs/live-review-history-verification.md`.

- [x] Запустить targeted-тесты новых компонентов, затем из корня:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" ./gradlew testDebugUnitTest assembleDebug
  ```
  Успех — свежий `BUILD SUCCESSFUL`; упавшие тесты фиксировать, не замалчивать.
  Результат 2026-10-30: `--rerun-tasks` → `BUILD SUCCESSFUL`, 301 тест, 0 failures, 0 errors, 0 skipped. После исправлений по независимому review (см. последний пункт задачи): `--rerun` → `BUILD SUCCESSFUL`, 325 тестов, 0 failures, 0 errors, 0 skipped.
- [x] Compose/device-тесты — только на отдельно согласованном target; не использовать Gradle connected cleanup на пользовательском телефоне; перед install-in-place получить разрешение и serial; не сбрасывать сессию, не чистить logcat.
  Выполнено: только install-in-place по serial `<device-serial>`; сессия и logcat не очищались; `connectedDebugAndroidTest` не запускался.
- [x] Физический smoke: Awake, фокус `MainActivity`, свежий PID; карусель с превью; открытие History; декодированный первый кадр HLS; sustained playback без повторного BUFFERING; ±10 s; перемотка по шкале; смена камеры с сохранением времени; Back к Live с восстановлением скролла; уход с Live освобождает media, activity socket остаётся; ON_STOP останавливает оба транспорта; screen off/on.
  Выполнено, отчёт и скриншоты: [docs/live-review-history-verification.md](../../live-review-history-verification.ru.md).
- [x] Viewed POST на device — только для явно разрешённого тестового segment; иначе подтвердить мутацию на mock transport и явно назвать ограничение runtime-проверки.
  Выполнено: реальная отметка выполнена один раз, для alert, отмеченного вручную; остальные проверки шли с подавленной мутацией. Ограничение названо в отчёте.
- [x] Сверить по свежим PID/time release Live media при History и release VOD при уходе; скриншоты с приватными кадрами не публиковать.
  Выполнено: `generation=1/2/3`, release media и socket по свежему PID; скриншоты с кадрами остались в job tmp, в документ не попали.
- [x] Выполнить одно независимое review всего изменения, исправить подтверждённые проблемы, повторить соответствующие проверки; в отчёте указать расхождения checkout ↔ развёрнутая версия PWA и всё, что не проверено на устройстве.
  Выполнено: независимый read-only review вернул 11 находок; восемь подтверждённых дефектов исправлены (R1–R6, R8, R10) с RED, доказанным мутацией прежнего поведения (141 тест, 13 failures на 8 мутаций), и перепроверены на устройстве; три находки признаны не дефектами и зафиксированы как расхождения с PWA (R7, R9, R11). Финальный прогон — 325 тестов, 0 failures, APK пересобран и установлен. Отчёт: [docs/live-review-history-verification.md](../../live-review-history-verification.ru.md), разделы 4 и 6–8.
