**[English](2026-11-01-pwa-events-android.md)** | Русский

# PWA Events → Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for task-by-task native execution; use superpowers:subagent-driven-development only if the user chooses that method. Steps use checkbox (`- [ ]`) syntax for tracking. No commits without an explicit user request.

**Goal:** Заменить legacy Events полным согласованным разделом: Alerts, Detections, Motion, History, фильтры, свайпы, мультивыбор, Export/Delete и wide timeline.

**Architecture:** Отдельная Events session и suspend repository поверх существующего FrigateClient; общий activity WebSocket служит invalidation для Live и Events. History и защищённые thumbnails переиспользуются. Native preview отделён от live MSE; фильтры, Motion, selection, heatmap и timeline моделируются без Android-зависимостей.

**Tech Stack:** Kotlin 2.2.10, AGP 9.4.1, Java 21, Android API 31–35, Compose BOM 2024.12.01, Material 3, Coroutines, Gson, OkHttp 4.12.0, Media3 1.9.0; JUnit 5 JVM, JUnit 4 instrumentation.

**Spec:** [Events design](../specs/2026-11-01-pwa-events-android-design.ru.md), [принятый макет rev4](../../design/pwa-events-tab.html). Пользователь явно принял макет сообщением «принимаю». Этот документ заменяет прежний план с самовольно исключёнными функциями.

## Global Constraints

- Android 12+, minSdk 31, compileSdk/targetSdk 35. Без новых библиотек, WebView, Hilt, Retrofit, Room, DataStore и Kotlin Serialization.
- Весь scope входит в результат. Порядок задач не означает перенос Motion, свайпов, выбора, Export/Delete или timeline во «вторую фазу».
- Narrow: три типа и **нет timeline**. Wide от 600 dp: сетка и правая шкала. Camera chip сохраняется. Desktop hover/keyboard/mouse-wheel не переносим; touch pinch переносим.
- Cookie только в Cookie header; origin/base path ServerProfile; redirects не переносят session; TLS не отключать, секреты не логировать. JSON ≤16 MiB; видео потоковое, не целиком в RAM.
- Любой новый 401 → существующий SessionRenewal. Обычная ошибка Events не переводит приложение в Loading/LoginRequired. CancellationException пробрасывать.
- Перед редактированием читать файл и соседние тесты, перед изменением функции искать всех callers, включая test fakes/androidTest. Никаких коммитов без явного запроса.
- TDD: RED правильной причины → минимальный GREEN. Итог: full JVM, debug build, device runtime для native preview/History/lifecycle.
- На устройстве только `adb -s <serial> install -r` + `am instrument`; не connectedAndroidTest, не uninstall/clear data/logcat и не системные настройки поворота.
- Не писать тесты на HTML-макет. Подготовка этого плана не является Android-реализацией.

## Review Focus

1. Старый ответ камеры A после выбора B/смены cookie не заменяет новую выборку — Task 3 `staleGenerationCannotCommit`.
2. Старый день, DST и повторный tap того же review открывают правильную камеру и start−4, не сегодняшний край — Task 5 `historicalReviewAlwaysRestartsAtPaddedStart`.
3. Motion с объектом, дубли bucket и неполная heatmap не становятся «чистым движением» — Tasks 7–8 `motionOnlyExcludesObjectOverlap`, `missingHeatmapContributesToDenominator`.
4. 401, частичный batch и ошибочный Delete не выглядят как успешные операции — Tasks 2/9 `batchRetainsFailedItems`, `unauthorizedDoesNotCommitMutation`.
5. Большой день, отсутствующее preview и STOP во время decode не дают молчаливой потери карточек или зависшего media — Tasks 2/6/10 `denseTimestampReportsCapacity`, `stopReleasesEveryPreview`.

## Разбор PWA: проверенные контракты

Checkout: `<upstream-checkout>/frigate/`, branch `dev`, `v0.18.2-356-gff2cd6a41`, commit `ff2cd6a4179a4bf83a99ee0156939d3cc016d8af`. Версия развёрнутого сервера неизвестна. Anchors ниже относятся к checkout, не являются runtime-проверкой устройства.

| Поведение | Источник |
|---|---|
| Review/Motion grid и per-camera pane | `web/src/views/events/EventView.tsx:1011–1232,1577–1726` |
| Swipe left только ended+unreviewed; right inline preview; selection tap | `web/src/components/player/PreviewThumbnailPlayer.tsx:69–87`; `EventView.tsx:207–227` |
| Summary/banner | `web/src/components/dynamic/NewReviewData.tsx:23–49`; `web/src/pages/Events.tsx:464–486` |
| Motion Only и диапазоны | `web/src/hooks/use-camera-activity.ts:205–418` |
| Preview metadata и returned src | `web/src/hooks/use-camera-previews.ts:13–37` |
| Heatmap, frames, regions | `web/src/views/events/MotionPreviewsPane.tsx:156–213,272–280,755–849,1007–1053` |
| Selection/export/delete | `web/src/components/filter/ReviewActionGroup.tsx:46–94,160–258`; `web/src/components/overlay/MultiExportDialog.tsx:170–252` |
| Delete удаляет и overlapping recording files/rows | `frigate/api/review.py:564–607` |
| Export queued/partial results/roles | `frigate/api/export.py:674–956` |

Ключевые решения:
- Данные — `/api/review`, не object `/api/events`. Severity/reviewed фильтруются клиентом, summary отдельный, refresh 30 s. Last 24 Hours — rolling window, день — границы календаря в той же timezone, что summary.
- Motion — `significant_motion`: синхронный preview grid и отдельный per-camera clips pane, не выдуманный график вместо сетки.
- Motion Only означает **движение без object review**; исключает alert/detection либо reviews с detections/objects. Ongoing review заканчивается в now.
- PWA имеет narrow timeline, но прямое требование пользователя — narrow без неё. Camera chip — согласованное отличие. Числа 206/3 относятся только к демонстрации.
- HTML preview синтетический, Export/Delete локальные, Motion Only не выполняет skip, heatmap отсутствует. Это ограничения демо, **не исключения Android scope**.

## Текущее состояние

- `MainActivity.kt:542`: legacy EventsScreen/EventCard; refresh меняет глобальный connection. `ConnectionHelpers.kt:17` загружает `/api/events` при подключении.
- `core/server/FrigateRepository.kt`: существующий ReviewRepository содержит loadReviews/markReviewsViewed. Не добавлять туда все Events API и не ломать carousel fakes.
- `FrigateClient.reviews/reviewQuery` уже имеют severity/reviewed/limit/before/after. Новые labels/zones добавлять в конец с defaults, сохраняя positional callers.
- `feature/review/ReviewSession.kt`/`ReviewController.kt` обслуживают Live-карусель последнего часа, не полную Events. ReviewUpdates/scheduler пригодны для invalidation.
- ReviewThumbnailLoader/ReviewMediaEndpoint защищают thumbnail paths. Loader сейчас превращает HTTP/decode failures в null: Events path должен отличать 401, missing и decode failure, не ослабляя origin/payload checks.
- HistoryNavigation.openReview уже делает padding, но его initial range и resume того же review не подходят для исторической даты Events. Добавить явный overload, не ломая Live resume.
- В рабочем дереве ранее преждевременно добавлены **7 RED-тестов**: 4 в FrigateApiParserTest и 3 в FrigateClientTest. Они обращаются к отсутствующим production API. Не удалять и не выдавать baseline за passing. Tasks 1–2 совместно восстанавливают test compilation.

## Файловая карта

Пути ниже относительно корня. Сокращения: `main = app/src/main/java/com/frigate/android`, `test = app/src/test/java/com/frigate/android`, `device = app/src/androidTest/java/com/frigate/android`.

| Файлы | Ответственность |
|---|---|
| `main/core/server/FrigateModels.kt`, `FrigateApiParser.kt`, `FrigateClient.kt` | DTO, parser, bounded authenticated HTTP |
| `main/feature/events/EventsModels.kt`, `EventsRepository.kt` | Запрос/snapshot/UI state; отдельный repository и client adapter |
| `main/feature/events/EventsController.kt`, `EventsSession.kt`, `EventsFilter.kt` | Generations, session/lifecycle, reconciliation, фильтры/counts/ranges |
| `main/feature/events/EventsScreen.kt`, `EventsHeader.kt`, `EventsDrawers.kt`, `ReviewGridCard.kt` | Принятый Compose layout, filters, selection, gestures |
| `main/feature/events/EventsHistoryEntry.kt` | Преобразование Events входа в существующий History |
| `main/feature/events/ReviewPreviewSource.kt`, `ReviewPreviewPlayer.kt` | Preview metadata, защищённое media, wall-clock mapping, native player |
| `main/feature/events/MotionModel.kt`, `MotionScreen.kt`, `MotionPreviewsPane.kt` | Общий clock, motion-only ranges, grid и clips |
| `main/feature/events/EventsTimelineModel.kt`, `EventsTimeline.kt` | Wide rail, overview, time mapping, pinch/tap |
| `main/feature/events/MotionRegionModel.kt`, `MotionRegionSheet.kt` | Heatmap, pending/apply region, filter/crop/dim |
| `main/feature/events/EventsActions.kt`, `EventsExportSheet.kt` | Mutations, export jobs/download, Delete confirmation |
| `main/feature/history/HistoryNavigation.kt`, `main/MainActivity.kt`, `ConnectionHelpers.kt` | Range-aware entry, origin/back, shared socket, удаление legacy loading |

Новые файлы создаются в owning task, не отдельным scaffolding-этапом. Не переносить весь раздел в MainActivity.

## Общие интерфейсы задач

Следующие имена — **планируемые** интерфейсы, не существующий API:

```kotlin
// EventsModels.kt
internal enum class EventsType { ALERT, DETECTION, MOTION }
internal data class EventsRange(val after: Double, val before: Double, val dayKey: String?)
internal data class EventsQuery(
    val cameraIds: List<String>, val range: EventsRange, val timezone: String,
    val labels: List<String> = emptyList(), val zones: List<String> = emptyList(),
)
internal data class EventsFilter(
    val type: EventsType = EventsType.ALERT, val showReviewed: Boolean = false,
    val includeOtherSeverity: Boolean = false,
)
internal data class EventsSnapshot(val items: List<FrigateReview>, val summary: FrigateReviewSummary)
internal data class ReviewOpenRequest(val review: FrigateReview, val range: EventsRange)

// EventsRepository.kt — existing ReviewRepository stays unchanged
internal interface EventsRepository {
    suspend fun load(query: EventsQuery): EventsSnapshot
    suspend fun markViewed(ids: List<String>, reviewed: Boolean)
    suspend fun delete(ids: List<String>)
    suspend fun previews(cameraIds: List<String>, range: EventsRange): List<ReviewPreview>
    suspend fun motion(query: EventsQuery): List<MotionPoint>
    suspend fun recordings(cameraId: String, range: EventsRange): List<MotionRecording>
    suspend fun previewFrames(cameraId: String, range: EventsRange): List<PreviewFrame>
    suspend fun exportSingle(review: FrigateReview, now: Double): ExportJob
    suspend fun exportBatch(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult
    suspend fun exports(): List<ExportJob>
    suspend fun exportCases(): List<ExportCase>
}
```

Normalized DTOs в EventsModels: `ExportCase(id:String,name:String,description:String?)`; `ReviewPreview(cameraId:String,startTime:Double,endTime:Double,src:String)`; `PreviewFrame(cameraId:String,timestamp:Double,src:String)`; `MotionRecording(startTime:Double,endTime:Double,heatmap:Map<Int,Int>)`; `ExportJob(id:String,status:String,downloadPath:String?)`; `ExportBatchResult(succeeded:Map<String,ExportJob>,failed:Map<String,String>)`, keys=client_item_id; `ExportCaseChoice` — существующий id либо новое name/description, взаимно исключены. Raw server export/preview fixtures закрепить в Task 2: нельзя угадывать ready/url поля.

EventsUiState: query, filter, items, summary, selectedIds:Set<String>, loading:Boolean, refreshFailed:Boolean, newItemsAvailable:Boolean, pendingIds:Set<String>, message:String?. Nullable summary отличает loading от реального нуля. EventsController.state:StateFlow<EventsUiState>; Unit-команды `setQuery(query:EventsQuery)`, `setFilter(filter:EventsFilter)`, `setActive(active:Boolean)`, `refresh()`, `onReviewsMessage(raw:String)`, `onReconnect()`, `toggleSelected(id:String)`, `clearSelection()`, `close()`, `applyMutation(ids:List<String>,reviewed:Boolean?,deleted:Boolean)`.

## Команды проверки

Из корня перед Gradle:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

JVM: `./gradlew testDebugUnitTest --tests '<полное имя класса>'`. Ниже класс `feature/events/XTest` означает `com.frigate.android.feature.events.XTest`; server/history аналогично. RED должен падать нужной проверкой/missing API, не unrelated compilation/network/toolchain. После Tasks 1–2 все новые RED должны воспроизводить именно поведение.

---

### Task 1: Review DTO и summary

**Files:** Modify `main/core/server/FrigateModels.kt`, `FrigateApiParser.kt`; Test existing `test/core/server/FrigateApiParserTest.kt`.

**Interfaces:** Consumes FrigateReview; produces appended default fields audioLabels:List<String>, subLabels:List<String>, detections:List<String>, threatLevel:Int?, title:String?; FrigateReviewCounts(totalAlert:Int,reviewedAlert:Int,totalDetection:Int,reviewedDetection:Int), FrigateReviewSummary(last24Hours:FrigateReviewCounts?,days:Map<String,FrigateReviewCounts>); `FrigateApiParser.parseReviewSummary(json:String):FrigateReviewSummary`.

- [ ] Прочитать parser/callers; сохранить 4 existing RED tests. Добавить `preservesDetectionIdsForMotionExclusion`: detections не теряются, optional metadata не выдумывает threat/title; missing summary day отличается от нуля.
- [ ] Запустить FrigateApiParserTest: RED missing fields/API. Если 3 client RED блокируют test compilation, зафиксировать зависимость и выполнить Task 2, не скрывать её удалением тестов.
- [ ] Дополнить model/parser defaults в конце конструктора, не ломая positional callers. Summary разделяет last24Hours и календарные keys; неправильные optional поля не ломают весь список.
- [ ] После Task 2 повторить parser class: GREEN со всеми старыми тестами.

### Task 2: HTTP и bounded Events repository

**Files:** Modify `main/core/server/FrigateClient.kt`, `FrigateApiParser.kt`; Create `main/feature/events/EventsModels.kt`, `EventsRepository.kt`; Test existing `test/core/server/FrigateClientTest.kt`; Create `test/feature/events/EventsRepositoryTest.kt`, `EventsMediaEndpointTest.kt`.

**Interfaces:** Consumes Task 1; produces общие interfaces/DTO выше, `ClientEventsRepository(client:FrigateClient):EventsRepository`; `FrigateClient.reviewSummary(cameraIds:List<String>,timezone:String,labels:List<String> = emptyList(),zones:List<String> = emptyList()):FrigateReviewSummary` и `reviewSummaryQuery` с теми же аргументами. Append labels/zones defaults к reviews/reviewQuery, не менять ReviewRepository.

- [ ] Сохранить 3 existing client RED. Добавить MockWebServer fixtures для таблицы ниже: `/frigate` base path, encoded filters/timezone, Cookie/CSRF, 401/403, 302 без второго запроса, 200 с application failure, oversized JSON, malicious media src.
- [ ] RED `denseTimestampReportsCapacity`: насыщенное окно делится, одинаковые timestamp при исчерпанной гранулярности дают явную capacity error; `overlappingWindowDeduplicatesBoundaryIds` сохраняет все id; summary не подменяется page size.
- [ ] Запустить client/repository classes: RED. Реализовать endpoint methods/adapter на IO dispatcher, cancellation пробрасывать.

| Endpoint | Запрос/ответ |
|---|---|
| GET `review` | cameras, optional labels/zones, before/after; без severity/reviewed для Events; limit=2000 на подокно как memory bound |
| GET `review/summary` | cameras, optional labels/zones, timezone; counts всех severity |
| GET `preview/{camera или all}/start/{round(after)}/end/{round(before)}` | Metadata; use returned src после validation |
| GET `review/activity/motion` | cameras CSV, before/after aligned30s, scale15; response camera может быть CSV |
| GET `{camera}/recordings` | after=floor(min range start), before=ceil(max range end); main-stream motion_heatmap |
| GET `preview/{camera}/start/{floor(after)}/end/{ceil(before)}/frames` | Filename list; image path `preview/preview_{camera}-{timestamp}.webp/thumbnail.webp` |
| POST `reviews/viewed` | `{ids:[...],reviewed:boolean}` |
| POST `reviews/delete` | `{ids:[...]}`; admin server enforcement |
| POST `export/{camera}/start/{start-4}/end/{end+4 или now}` | Single `{playback:"realtime",image_path:thumb_path}` как PWA; ongoing end=now без +4. Checkout server ignores playback и defaults recordings/auto |
| POST `exports/batch` | UI 2..50; `{items:[{camera,start_time:start-4,end_time:(end??now)+4,image_path?,friendly_name?:trimmed title,client_item_id:id}],export_case_id? либо new_case_name?,new_case_description?}` |
| GET `cases` | Существующие export cases для admin-only selector; API допускает authenticated, но Android следует PWA UI gate. Новый case создаётся в batch payload, не отдельным POST |
| GET `exports` | 202 — queued, не completed; разбирать фактические job fields/ready state, не угадывать download URL |

- [ ] Полная bounded загрузка: рекурсивно делить насыщенные временные окна с перекрытием границ, dedup по id; max4 concurrent requests, depth≤24, максимум20000 unique items. При пределе — typed capacity error «сузьте дату/камеры», не silently truncated success. Предыдущий snapshot остаётся видимым; это явная engineering safety policy, не upstream pagination.
- [ ] Preview/export raw DTO закрепить fixtures из checkout API; normalized DTO задаёт только приложение. Returned src/download path запрещают cross-origin, credentials, traversal/base-path escape; redirects не следовать. Empty camera selection не превращать в all доступов сервера.
- [ ] Запустить parser/client/repository/endpoint tests, затем `./gradlew testDebugUnitTest`: GREEN. Только здесь baseline с 7 преждевременными RED считается восстановленным.

### Task 3: Session, filters/counts и reconciliation

**Files:** Create `main/feature/events/EventsController.kt`, `EventsSession.kt`, `EventsFilter.kt`; Test `test/feature/events/EventsControllerTest.kt`, `EventsFilterTest.kt`.

**Interfaces:** Consumes EventsRepository; produces EventsUiState/controller выше; `eventsRange(dayKey:String?,timezone:String,now:Double):EventsRange`, `filteredReviews(items:List<FrigateReview>,filter:EventsFilter):List<FrigateReview>`, `reviewCounts(summary:FrigateReviewSummary,dayKey:String?,showReviewed:Boolean):Pair<Int,Int>`.

- [ ] RED `countsUseSummaryNotLoadedCards`: total12/reviewed3→9, showReviewed→12; missing day→0, loading→spinner. Last24h rolling; DST day23/25h; reversed range rejected. `staleGenerationCannotCommit`, cancellation не показывает error, STOP прекращает refresh, reconnect делает REST.
- [ ] RED banner: без explicit day, not loading, nonempty loaded list и summary expected>loaded filtered→banner. Empty+positive expected→auto refresh. Historical day/Motion/capacity load не выдаются за «новые события».
- [ ] Запустить оба класса: RED. Реализовать generation token на query/profile/cookie, старый snapshot сохранять при refresh failure. State над History branch, polling только Events visible+STARTED; summary refresh30000ms, resume/reconnect REST, WS coalescing через ReviewUpdates.
- [ ] Тип/ShowReviewed фильтруют локально; новые camera/date/labels/zones создают query. Motion запросы не несут labels/zones. Смена query/type очищает incompatible selection/preview. Apply banner reload+scrollTop, не автопрыжок при WS.
- [ ] Повторить оба класса: GREEN, включая retention state при History.

### Task 4: Review UI, filters и gestures

**Files:** Create `main/feature/events/EventsScreen.kt`, `EventsHeader.kt`, `EventsDrawers.kt`, `ReviewGridCard.kt`; Test `device/feature/events/EventsScreenTest.kt`, `EventsGesturesTest.kt`.

**Interfaces:** Consumes EventsUiState/controller; produces `EventsScreen(state:EventsUiState,onOpenReview:(ReviewOpenRequest)->Unit,onAction:(EventsAction)->Unit,modifier:Modifier=Modifier)`. EventsAction — sealed typed actions в EventsModels: SelectType, SetCameras, SetDate, SetLabels, SetZones, SetReviewedFilter, SetIncludeOtherSeverity, ToggleSelection, ClearSelection, MarkViewed, Preview, ReloadNewItems, ExportSelection, DeleteSelection; payload type соответствующего query/filter/id, не string commands.

- [ ] RED Compose: narrow три типа/no rail, wide rail; gear не toggles reviewed; cameras/date/labels/zones меняют именно query; reviewed влияет на list/counts, filtered-empty отличается от initial-empty.
- [ ] RED gestures: ended+unreviewed left→одно MarkViewed; ongoing/reviewed left→ноль; right→Preview без OpenHistory; vertical→ноль; long press→selection без tap; selected tap toggles. Semantics actions достигают того же без swipe.
- [ ] Запустить новые device classes на fixture host через recipe Task 10: RED по отсутствующей UI/семантике, не network. Не менять launcher ради теста.
- [ ] Реализовать rev4: dark sheets/popups, camera chip, conditional metadata, ongoing, severity outline, replacing-header selection toolbar. Platform ViewConfiguration задаёт touchSlop/longPressTimeout; demo55px/550ms не копировать в production dp. Touch targets доступные, цвет не единственная semantics.
- [ ] Повторить device classes narrow/wide: GREEN; визуально сравнить с rev4, не считать semantics проверкой fidelity.

### Task 5: Events → History → Events

**Files:** Modify `main/feature/history/HistoryNavigation.kt`, `main/MainActivity.kt`; Create `main/feature/events/EventsHistoryEntry.kt`; Test existing `test/feature/history/HistoryNavigationTest.kt`, Create `test/feature/events/EventsHistoryEntryTest.kt`, `device/EventsHistoryNavigationTest.kt`.

**Interfaces:** Consumes ReviewOpenRequest; produces overload `HistoryNavigation.openReview(review:FrigateReview,range:HistoryRange,restart:Boolean)` и `openRecording(cameraId:String,timestamp:Double,range:HistoryRange)`; existing `openReview(review)`/Live resume сохраняются.

- [ ] RED `historicalReviewAlwaysRestartsAtPaddedStart`: исторический review start1710000100→playhead1710000096, правильная камера; повторное открытие снова там. Расширить нижний range до start−4 для полуночи, верх покрывает event end/now ongoing.
- [ ] RED `motionEntryHasNoFakeReviewAndNoPadding`: timestamp точный; grid ограничен now−30, clips exact start. Старые Live resume tests должны оставаться GREEN.
- [ ] Запустить оба JVM класса: RED; реализовать EventsRange→HistoryRange adapter/overload без изменения player/chunk/zoom. При открытии activeScreen остаётся Events; Back закрывает History и восстанавливает filters/type/scroll. onLive явно переключает Live.
- [ ] Инициировать markViewed сразу при открытии, не ждать first frame. Coroutine живёт в session над screen branch. Ошибка mark не закрывает History, показывает retry; 401→renewal.
- [ ] JVM GREEN; device две разные камеры/исторический день/Back/rotation. Первый реальный HLS frame проверяется в Task 10.

### Task 6: Inline native preview

**Files:** Create `main/feature/events/ReviewPreviewSource.kt`, `ReviewPreviewPlayer.kt`; Modify `ReviewGridCard.kt`; Test `test/feature/events/ReviewPreviewSourceTest.kt`, `ReviewPreviewPolicyTest.kt`, `device/EventsPreviewPlaybackTest.kt`.

**Interfaces:** Consumes repository previews/frames; produces `selectPreview(previews:List<ReviewPreview>,cameraId:String,range:EventsRange):ReviewPreview?`, `previewOffset(preview:ReviewPreview,wallClock:Double):Double?`; player consumes authenticated source/range/rate and `onUnauthorized:()->Unit`.

- [ ] RED greatest same-camera overlap, no matching source→unavailable, wall-clock offset правильный; no fake clip URL. Current-hour video missing→frame fallback, до3 следующих кадров и last-frame fallback; historical missing→unavailable, не spinner forever.
- [ ] RED `stopReleasesEveryPreview`: одна Review preview active; left/selection/type/query/hidden/History/STOP закрывают source/player. 401 отличается от404/decode и уведомляет host один раз generation.
- [ ] Запустить source/policy JVM: RED; Media3 progressive preview, audio off, loop range-relative offsets. Authenticated frame fallback с bounded bytes/cancellation. Максимум один decoder Review grid; no Cookie в MediaItem URL. Surface не поглощает Compose controls/gestures (D6).
- [ ] JVM GREEN; device decoded first frame + sustained time/frame advancement + release при уходе/STOP. Thumbnail/STATE_READY не доказательство playback. Если server previews отсутствуют, gate остаётся непроверенным, не PASS.

### Task 7: Motion grid и per-camera clips

**Files:** Create `main/feature/events/MotionModel.kt`, `MotionScreen.kt`, `MotionPreviewsPane.kt`; Test `test/feature/events/MotionModelTest.kt`, `device/EventsMotionPlaybackTest.kt`.

**Interfaces:** Consumes repository motion/previews, Task6 player; produces `motionOnlyRanges(points:List<MotionPoint>,reviews:List<FrigateReview>,cameraId:String,now:Double):List<EventsRange>`, `nextMotionTimestamp(current:Double,query:EventsQuery,points:List<MotionPoint>,reviews:List<FrigateReview>,motionOnly:Boolean,now:Double):Double?`.

- [ ] RED `motionOnlyExcludesObjectOverlap`: positive motion с alert/detection/detections/objects overlap исключается; ongoing end=now. Дубли start→MAX, camera CSV распознаётся; zero/negative/empty не рождает clip. Clip bucket15s, merge только contiguous±.001s, не произвольное overlap.
- [ ] RED rates ровно[4,8,12,16], default8; normal tick .5s, skip segments30s. Shared wall-clock seek для всех камер; gap одной не останавливает остальные. Grid inputs все selected reviews, clips только своей камеры. Выход из Motion очищает selected camera.
- [ ] Запустить MotionModelTest: RED; реализовать grid play/pause/seek/rate и реальный Motion Only skip; per-camera clips всегда motion-only независимо от toggle, choose greatest overlap source, loop clip range. Grid click min(time,now−30), clip click exact start без viewed mutation.
- [ ] Pool Motion max4 visible decoders, остальные камеры доступны прокруткой и подключаются к общему времени. Late/gap decoder не меняет общий clock; still/unavailable вместо зависшего чужого кадра. Выход/STOP releases pool.
- [ ] JVM GREEN; device минимум две камеры sync/seek/rate/gaps/skip и per-camera Back. Narrow no rail, wide rail подключается Task8.

### Task 8: Wide timeline и Motion regions

**Files:** Create `main/feature/events/EventsTimelineModel.kt`, `EventsTimeline.kt`, `MotionRegionModel.kt`, `MotionRegionSheet.kt`; Modify `MotionPreviewsPane.kt`; Test `test/feature/events/EventsTimelineModelTest.kt`, `MotionRegionModelTest.kt`, `device/feature/events/EventsTimelineTest.kt`, `MotionRegionTest.kt`.

**Interfaces:** Consumes range/reviews/motion/recordings; produces `EventsTimelineModel(range:EventsRange,items:List<FrigateReview>,motion:List<MotionPoint>)` with `timeAt(fraction:Double):Double`, `fractionAt(time:Double):Double`, `reviewAt(time:Double):String?`; `mergedHeatmap(recordings:List<MotionRecording>,range:EventsRange):Map<Int,Double>`, `matchesRegion(heatmap:Map<Int,Double>,cells:Set<Int>):Boolean`, `regionBounds(cells:Set<Int>):NormalizedRect?`; NormalizedRect(left,top,right,bottom:Double).

- [ ] RED timeline roundtrip/clamp, quarter-hour clock-aligned labels, DST repeated labels distinct timestamps, selected segment finds actual filtered card, pinch preserves filters and doesn't create narrow rail.
- [ ] RED `missingHeatmapContributesToDenominator`: intensity200×10s + missing×10s→100; no overlap→empty; index0..255, values clamp0..255. ANY selected positive cell matches, empty map excluded only with applied region.
- [ ] RED region pending before Apply, Cancel unchanged, camera switch clears; bounding rectangle crop, aspect clamp.75..4, crop defaulttrue, dim default82%, strongest alpha.06/sqrt normalization, letterbox respected.
- [ ] Запустить model classes: RED; реализовать wide rail/overview tap→scroll и touch pinch без wheel/keyboard. Spatial model —16×16 row-major, overlap weighting including missing records. Region picker background authenticated `GET /api/{camera}/latest.jpg?h=500`; dim нейтральный, не жёлтые rectangles. Нет heatmap→spatial controls unavailable с объяснением, остальные Motion работают.
- [ ] JVM GREEN; device wide/narrow rail/pinch/segment hit и region Apply/Cancel/crop. Не считать scalar temporal motion пространственными данными.

### Task 9: Mutations, Export/Delete

**Files:** Create `main/feature/events/EventsActions.kt`, `EventsExportSheet.kt`; Modify `EventsController.kt`, `EventsHeader.kt`; Test `test/feature/events/EventsActionsTest.kt`, `device/feature/events/EventsSelectionTest.kt`.

**Interfaces:** Consumes repository/controller; produces `EventsActions(repository:EventsRepository,onUnauthorized:()->Unit)` with suspend `mark(reviews:List<FrigateReview>):Unit`, `delete(ids:List<String>):Unit`, `export(reviews:List<FrigateReview>,now:Double,case:ExportCaseChoice?):ExportBatchResult`. Single export оборачивается в succeeded map. Controller.applyMutation commits successful IDs и invalidates summary/Live callback.

- [ ] RED empty selection→no request; mixed→reviewedtrue, all-reviewed→false; mark-all excludes ongoing. User не видит Delete/case UI, admin видит; export51 блокируется с объяснением. Backend authorization не заменять UI gate.
- [ ] RED `batchRetainsFailedItems`:3 results/2success/1fail→failed остаётся selected и ошибка видна; all-fail сохраняет dialog/selection; queued не downloaded. `unauthorizedDoesNotCommitMutation`:401→renewal, local mutation не committed, repeated submit one request.
- [ ] Запустить ActionsTest: RED; implement snapshot ids/pending guard. Single отправляется непосредственно как PWA, без придуманного обязательного dialog; batch2..50 открывает sheet с admin-only case controls. Не-admin export разрешён. Partial success разбирать per item, не HTTP202 alone.
- [ ] Delete только с confirm, явно предупредить об overlapping recording files; commit list/selection/summary/Live только после успешного ответа. Error сохраняет data/retry.
- [ ] Poll GET exports только active session/visible status; сетевой сбой не повторяет POST автоматически. Готовый файл потоково в app cache через same-origin authenticated download, затем system save/share без cookie-bearing Intent URL. Cancel save не меняет готовность job. Logout/profile/cookie switch отменяют download и session cache metadata.
- [ ] JVM GREEN; device fixture tests ролей/selection/dialog/result/error. Реальный Export — только разрешённые тестовые данные. **Не удалять реальные записи пользователя без отдельного явного разрешения**; delete success/failure покрыть mock transport.

### Task 10: Wiring, legacy cleanup, полная приёмка

**Files:** Modify `main/MainActivity.kt`, `ConnectionHelpers.kt`, `main/core/server/FrigateRepository.kt` (только unused loadEvents после callers); existing connection tests найденные caller search; Create `device/EventsLifecycleTest.kt`; Update `CLAUDE.md`, `docs/live-review-history-verification.md`.

**Interfaces:** Consumes все tasks; produces integrated Events, origin-preserving History, shared socket fan-out и Connected без blocking legacy events.

- [ ] RED connection test: `/api/events` failure не блокирует Connected; Events failure не глобальный Loading/LoginRequired. Shared reviews/reconnect уведомляет обе session, tab switch не дублирует socket; successful mutation refreshes carousel.
- [ ] RED lifecycle: rotation сохраняет query/type/scroll/History; STOP освобождает players/polling, START REST reconcile; cookie renewal не принимает старые responses/media. Existing Live/History/Settings regression остаётся рабочим.
- [ ] Запустить targeted classes: RED; replace private legacy EventsScreen/EventCard feature экраном. Удалить onRefreshEvents/Connected.events только после поиска всех constructors/tests/callers. Legacy client/parser APIs оставлять, если есть независимые callers; не делать unrelated cleanup.
- [ ] Fan-out существующего socket на reviewSession/eventsSession. State keys profile+cookie; mark coroutine переживает скрытие Events под History. Root/navigation dark для Events/History, Settings не менять без задачи. Renewal через existing callback.
- [ ] Выполнить `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`: полный JVM GREEN, APK собраны. Записать свежие test counts/failures, не копировать прежние результаты.
- [ ] Проверить awake/focus MainActivity/свежий PID/непустой screenshot. Safe installation/instrumentation:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  -e class com.frigate.android.EventsHistoryNavigationTest,com.frigate.android.EventsPreviewPlaybackTest,com.frigate.android.EventsMotionPlaybackTest,com.frigate.android.EventsLifecycleTest \
  com.frigate.android.test/androidx.test.runner.AndroidJUnitRunner
```

- [ ] Остальные новые UI classes и existing Live/History/session/rotation regression запускать тем же recipe с соответствующим class. Wide native fixture bounds≥600dp; при отсутствии физического wide-устройства отметить wide runtime как непроверенный отдельно от fixture layout.
- [ ] Runtime: реальные summary/filter/day; две камеры tap→History start−4→Back; swipe/selection; decoded preview + sustained advancement; Motion sync/seek/rate/skip/gaps/regions; permitted export/non-destructive mutations; network failure/401 renewal; screen off/on; rotation через requestedOrientation, не системные settings.
- [ ] Сверить narrow/wide screenshots с rev4, свежие PID/time-filtered logs без очистки. Обновить docs фактами и явными незакрытыми gates. Нет preview/heatmap/role данных — это «не проверено», не PASS; реальные Delete проверки без разрешения запрещены.

## Done и передача

Готовность — все scope-пункты spec реализованы и проверены соответствующим слоем. Build/HTML/unit не доказывают playback. Неустранимые условия теста записываются как незакрытые acceptance gates, а не скрываются исключением функций.

Самопроверка плана: три типа, filters/counts, History/back, gestures, selection/actions/export/delete, Motion grid/clips/regions, timeline, session/security и bounded data имеют owning task; пять Review Focus покрыты tests. ReviewRepository не ломается, прежние 7 RED учтены. При подготовке этого документа production-код, сборки, destructive actions и коммиты не выполнялись.
