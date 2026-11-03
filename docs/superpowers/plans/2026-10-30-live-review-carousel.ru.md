**[English](2026-10-30-live-review-carousel.md)** | Русский

# Live Review Carousel and Recording Playback Implementation Plan

> **SUPERSEDED / НЕ ВЫПОЛНЯТЬ.** 2026-10-30 пользователь отверг макет упрощённого bounded VOD player: требуется повторить существующий UX Frigate PWA в Native Android. Ограничение просмотра одним фрагментом и исключение архивной шкалы не согласованы. Задачи recording resolver/player/navigation ниже требуют переработки после исследования реального PWA History и согласования нового макета. Действующий план — `docs/superpowers/plans/2026-10-30-pwa-history-android.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить согласованную карусель непросмотренных alerts и отдельный native-экран просмотра записи выбранного события.

**Architecture:** Review segments имеют отдельные модель, REST и session-scoped controller; `/api/events` не используется как источник ленты. Review invalidations проходят независимым callback через существующий shared activity WebSocket, без второго соединения и без привязки к camera reducer. Отдельный Media3 HLS player воспроизводит ограниченный VOD-интервал; native MSE Live не меняется.

**Tech Stack:** Kotlin 2.2.10, Android 12+, Compose BOM 2024.12.01, Coroutines, Gson, OkHttp 4.12.0, Media3 1.9.0, JUnit 5/Jupiter и существующие Compose instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-10-30-live-review-carousel-design.md`; визуальная основа `docs/design/live-unreviewed-carousel.html`, принята пользователем 2026-10-30. Пользователь также выбрал открытие записи по нажатию. Ограниченный VOD вместо полного архивного интерфейса — рекомендуемый scope этого плана.

## Global Constraints
- Работать in-place, без commits/worktree, без очистки данных/настроек устройства или logcat.
- Перед edit прочитать файл и соседние тесты; перед изменением функции найти всех callers.
- TDD: targeted RED по правильной причине → минимальный GREEN → полный suite и debug build. Не смешивать изменение MSE policy с добавлением review.
- Не читать/искать `build`, `node_modules`, `.git`, `dist`, `__pycache__`. Не публиковать credentials/cookies/private camera imagery.
- Все URL relative to ServerProfile с сохранением deployment prefix; cookie только в заголовках своего origin; redirects запрещены; TLS verification включена.
- До 10 непросмотренных alerts, `startTime > now - 3600`, новые сначала. Refresh 1/6/5 s для end/new-or-genai/object-growth; periodic reconciliation 30 s только на foreground Live.
- Touch targets >=48 dp. Production без новой library family; добавить лишь Media3 HLS и OkHttp datasource версии 1.9.0.
- JVM/compile не доказывают playback. Реальные viewed writes меняют статус пользователя: runtime mutation только на разрешённом тестовом segment.

## Review Focus
1. Вложенный HLS playlist/key/segment с чужим origin или redirect: до любого Cookie/network I/O отклонить; доказать двумя тестовыми origins.
2. GET, начавшийся до успешного viewed POST, завершается позже: карточка не возвращается; logout/session switch отвергает все старые callbacks.
3. Review WS не меняет camera activity: REST revalidation всё равно происходит; socket не пересоздаётся при смене вкладки/detail.
4. Full-span carousel в grid меняет viewport: не запускать невидимую камеру и сохранить scroll после Back из записи.
5. Ongoing/ещё не записанное событие и STOP во время buffering: конечный валидный диапазон, явная ошибка/refresh и release без фонового player.

---

### Task 1: Review contract, parsing and safe endpoints

**Files:** modify `core/server/FrigateModels.kt`, `FrigateApiParser.kt`, `FrigateClient.kt`, `FrigateRepository.kt`; create `core/server/ReviewMediaEndpoint.kt`, соответствующие JVM-тесты в `app/src/test/java/com/frigate/android/core/server/`.
Пути production здесь и ниже относительно `app/src/main/java/com/frigate/android/`.

**Interfaces:**
- `FrigateReview(id: String, camera: String, startTime: Double, endTime: Double?, severity: String, hasBeenReviewed: Boolean, thumbnailPath: String?, objectLabels: List<String>)`.
- `FrigateApiParser.parseReviews(body: String): List<FrigateReview>`.
- `FrigateClient.reviews(cameraIds: List<String>, limit: Int = 10): List<FrigateReview>` и `markReviewsViewed(ids: List<String>): Unit`; repository suspend `loadReviews(cameraIds: List<String>): List<FrigateReview>`, `markReviewsViewed(ids: List<String>): Unit`.
- `ReviewMediaEndpoint.thumbnailUrl(profile: ServerProfile, thumbnailPath: String?): String?` — только ожидаемый review thumbnail path, иначе null.

- [ ] **RED:** `FrigateApiParserTest` проверяет distinct review ID, Unix seconds Double, null end/thumbnail/data, objects; повреждённые обязательные поля не создают выдуманные ID/времена. `FrigateClientTest` проверяет GET `api/review` с `limit=10&severity=alert&reviewed=0&cameras=…`, POST `api/reviews/viewed` с ids/reviewed=true, Cookie, deployment prefix, escaping, 401/403 и отказ redirect. Пустой cameraIds возвращает пусто без запроса всех камер.
- [ ] **RED:** `ReviewMediaEndpointTest` проверяет `/media/frigate/clips/review/…` → `{base}/clips/review/…`; absolute URL, protocol-relative URL, traversal, encoded traversal и другой path возвращают null.
- [ ] Запустить новые targeted-тесты и зафиксировать правильные failures. Реализовать интерфейсы через существующий `FrigateClient.request` и Gson parsing; не дублировать login/session storage.
- [ ] Повторить targeted suite до GREEN.

### Task 2: Review state and refresh on existing WebSocket

**Files:** create `feature/review/ReviewUpdates.kt`, `ReviewController.kt`, `ReviewSession.kt`; modify `feature/live/CameraActivityWebSocket.kt`, `CameraActivitySession.kt`, `MainActivity.kt`; tests `feature/review/ReviewControllerTest.kt`, `ReviewUpdatesTest.kt` и существующие transport/navigation tests.

**Interfaces:**
- `reviewRefreshDelayMillis(raw: String): Long?`: decode Frigate envelope + nested reviews payload; end=1000, new/genai=6000, objects growth=5000, иначе null.
- `unreviewedAlerts(items: List<FrigateReview>, cameraIds: Set<String>, nowSeconds: Double): List<FrigateReview>`: allowed cameras, alert, !reviewed, rolling hour, distinct id, descending start, limit10.
- `ReviewUiState(items: List<FrigateReview>, loading: Boolean, refreshFailed: Boolean, markingIds: Set<String>, markFailedIds: Set<String>)`.
- `ReviewController(repository: FrigateRepository, scope: CoroutineScope, nowSeconds: () -> Double)` exposes `StateFlow<ReviewUiState>` and `setActive(active: Boolean, cameraIds: Set<String>)`, `invalidate(delayMillis: Long = 0)`, `markViewed(id: String)`, `close()`.
- `rememberReviewSession(profile, cookie, active, cameraIds, onUnauthorized)` владеет controller, keyed by profile/cookie; его invalidation callback передаётся в shared socket. Оба callbacks socket default/no-op и не меняют существующий return Map `rememberCameraActivity`.

- [ ] **RED:** synthetic WS new/end/genai/object-growth/malformed payload; unchanged camera state не подавляет review update. Initial/ON_START/reconnect/Live re-entry вызывает REST; STOP/tab hide отменяет periodic/network work и помечает dirty. Повторные invalidations сохраняют ближайший дедлайн, запросы не перекрываются, пришедший во время GET сигнал не теряется.
- [ ] **RED:** boundary `now-3600` исключается, ongoing с null end сохраняется, now advances без WS удаляет старые строки; permissions/camera filtering и duplicate IDs. Inject clock/scope и управляемые deferred responses; не добавлять kotlinx-coroutines-test без необходимости.
- [ ] **RED:** failed refresh сохраняет items; failed mark сохраняет карточку с Retry; successful mark удаляет и refills через REST; pre-mark GET не воскрешает карточку. Два нажатия не дают два одновременных POST. Старый generation после смены cookie/профиля не публикуется.
- [ ] Реализовать. Dispatch review callbacks до reducer equality early-return; publish reconnect callback после generation check. Использовать `rememberUpdatedState` для свежих callbacks без socket recreation. Не извлекать общий WS framework и не добавлять второй socket.
- [ ] 401 направлять владельцу подключения через callback в существующий LoginRequired flow; 403/404/transport — локальная ошибка без сброса всех camera states. Проверить все callers при расширении ConnectedApp.
- [ ] Запустить targeted GREEN, затем существующие camera reducer/socket tests без изменения прежних expectations.

### Task 3: Authenticated thumbnails and carousel UI

**Files:** create `feature/review/ReviewThumbnailLoader.kt`, `ReviewCarousel.kt`; modify `feature/live/LiveCameraScreen.kt`; JVM loader tests и `app/src/androidTest/java/com/frigate/android/ReviewCarouselTest.kt`.

**Interfaces:**
- `ReviewThumbnailLoader.load(profile: ServerProfile, cookie: String, review: FrigateReview): ByteArray` — suspend cancellable fetch; invalid/missing URL приводит к fallback UI без запроса.
- `ReviewCarousel(state: ReviewUiState, cameraNames: Map<String,String>, onOpen: (FrigateReview) -> Unit, onMarkViewed: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier)`.
- Live screen принимает review state/callbacks и переданный `LazyGridState`, созданный в ConnectedApp над branch detail.

- [ ] **RED:** loader tests на Cookie, base path, redirects, отмену, non-image/error; ограничить response size до 2 MiB и decode dimensions до выделения full bitmap. Использовать маленький bounded session-local cache на 10 превью и off-main decode, без latest.webp polling. Обновление ongoing thumbnail при REST refresh не должно навсегда оставлять прежнее изображение.
- [ ] **RED Compose:** loaded/empty/loading/error/fallback/ongoing, long camera name, крупный текст; mark label, >=48dp, callback ID; image open и viewed — разные callbacks, появление/scroll не вызывает ни один.
- [ ] Реализовать согласно принятому HTML. Карусель — full-span item существующего LazyVerticalGrid; фильтровать item keys при camera visibility, чтобы review header не был camera ID. Detail камеры ленту не показывает.
- [ ] **GREEN regression:** видимость камеры 49/50%, сдвиг header height, неизменная camera identity сохраняет player; пустая лента не оставляет gap. Не менять NativeMsePlayer/Go2RtcMseDataSource/buffer policy.

### Task 4: Safe recording resolver and HLS transport

**Files:** create `feature/review/RecordingRequest.kt`, `RecordingHttpDataSource.kt`; modify `gradle/libs.versions.toml`, `app/build.gradle.kts`; tests `RecordingRequestTest.kt`, `RecordingHttpDataSourceTest.kt`.

**Interfaces:**
- `RecordingRequest(reviewId: String, cameraId: String, startSeconds: Double, endSeconds: Double, ongoing: Boolean, uri: String)`.
- `resolveRecording(profile: ServerProfile, review: FrigateReview, nowSeconds: Double): RecordingRequest`: start=max(0,startTime-4), end=min(now,endTime+4), либо now для ongoing; finite end>start обязателен. URI `vod/{camera}/start/{start}/end/{end}/master.m3u8`, без `api/`, locale-independent timestamp.
- `recordingDataSourceFactory(profile: ServerProfile, cookie: String): DataSource.Factory`, на Media3 OkHttp datasource; guard для каждого `DataSpec` до open и redirects=false в клиенте.

- [ ] **RED:** completed/ongoing/zero/NaN/future/negative ranges; encoded camera; prefix URL, нет stream-ID подмены. Статус missing recordings не подменяется live.
- [ ] Добавить только `media3-exoplayer-hls` и `media3-datasource-okhttp` с общей version.ref media3=1.9.0.
- [ ] **RED transport:** synthetic master → child playlist → init/map → segment/key и Range запросы получают Cookie своего origin; manifest с чужим origin/схемой отклоняется до подключения к другому MockWebServer. Redirect на тот же/другой host отклоняется без follow. Никакие DataSpec headers не должны переопределять session Cookie чужим значением. Проверить query URLs без печати secrets.
- [ ] Реализовать factory с ограничением http(s) и того же scheme/host/effective-port; локальные/asset/content URIs из manifest запрещены. Media3 source HLS и unit-тесты используют одну factory, не отдельную незащищённую загрузку дочерних ресурсов.
- [ ] Targeted GREEN. Контроль API по Context7 выполнен: `/androidx/media`, HlsMediaSource.Factory и lifecycle release; docs release могут быть новее 1.9.0, поэтому совместимость подтверждает compile pinned версии.

### Task 5: Recording screen and navigation

**Files:** create `feature/review/RecordingPlayer.kt`, `RecordingDetailScreen.kt`; modify `MainActivity.kt`; extend `app/src/androidTest/java/com/frigate/android/LiveNavigationTest.kt` и добавить `RecordingDetailTest.kt`.

**Interfaces:**
- `RecordingDetailScreen(profile: ServerProfile, cookie: String, review: FrigateReview, cameraName: String, markFailed: Boolean, onRetryMark: () -> Unit, onBack: () -> Unit, onUnauthorized: () -> Unit)`.
- В ConnectedApp `selectedReview` отделён от `selectedCameraId`; Back и выбор другой вкладки закрывают запись. Grid state хранится выше detail branch, keyed by session.

- [ ] **RED UI/navigation:** tap review открывает запись, а не live-камеру; shared activity socket остаётся одним, Live player/polling освобождаются; Back восстанавливает grid position; таб закрывает detail. Исчезновение строки после mark не закрывает выбранную запись.
- [ ] Создать отдельный ExoPlayer/HlsMediaSource с factory задачи 4, обычной VOD скоростью 1.0 и PlayerView в AndroidView. Не переиспользовать native MSE source/extractors/catch-up policy. По умолчанию mute; audio controls не добавлять.
- [ ] Play/pause, seek, buffering/ended, error+Retry, «запись пока недоступна» для 404, forbidden для 403, authentication callback для 401. Ongoing status и refresh перестраивают bounded request с новым now; не поддерживать бесконечный auto-retry.
- [ ] **RED→GREEN lifecycle:** STOP/dispose освобождает player и view binding, START recreates с сохранёнными position/playWhenReady; поздний callback старой generation не меняет UI; Retry даёт новую identity. Сохранять позицию отдельно для review/session и не переносить её в другое событие.
- [ ] Открытие запускает markViewed через controller независимо от media readiness; failed viewed write показывает локальную ошибку и Retry, не выдаёт ложный успех и не блокирует сам просмотр. Никогда не отмечать по render/first frame.

### Task 6: Full verification and acceptance report

**Files:** update `docs/testing-strategy.md`, `docs/live-design-review.md`, `CLAUDE.md`; create `docs/live-review-carousel-verification.md` с точными результатами и ограничениями.

- [ ] Запустить targeted tests всех новых компонентов, затем из корня с явным Java21:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" ./gradlew testDebugUnitTest assembleDebug
  ```
  Успех — свежий `BUILD SUCCESSFUL`; вывод failed tests фиксировать, а не замалчивать.
- [ ] Compose/device tests запускать на выделенном согласованном target; не использовать Gradle connected cleanup на пользовательском телефоне. Перед install-in-place отдельно получить разрешение и serial. Не сбрасывать сессию и не очищать logcat.
- [ ] Физический smoke: Awake, MainActivity focus, fresh PID; list/thumbnail/scroll/open recording/decoded first frame/pause/seek/sustained playback/Back/grid scroll/Live MSE/tab/screen off-on. Для ongoing — конечный интервал и refresh, для отсутствующей записи — Retry без spinner loop.
- [ ] Реальный viewed POST только на отдельно разрешённом тестовом segment. Иначе подтвердить mutation на mock transport, а production статус не менять автоматически и явно назвать это ограничение runtime проверки.
- [ ] Проверить по свежим PID/time release Live media при записи и release VOD при STOP; shared activity не теряется при navigation. Screenshots с частными кадрами не публиковать.
- [ ] Выполнить одно независимое review всего изменения, исправить подтверждённые проблемы и повторить соответствующие проверки. Документировать source reference vs deployed-version caveat и всё не проверенное на физическом устройстве.

## Выполнение
Рекомендуемый способ — последовательно самим в текущей сессии: задачи связаны model/controller/navigation интерфейсами, дробление на исполнителей добавит передачу большого контекста. Один независимый review после выполнения. До подтверждения данного scope production-код не менять; коммитов не делать.
