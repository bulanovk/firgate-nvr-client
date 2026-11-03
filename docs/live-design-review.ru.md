**[English](live-design-review.md)** | Русский

# Live — дизайн-ревью и реализация

Дата: 2026-10-29.

Последующее исправление: [tap камеры и статусы при смене вкладок](live-navigation-regressions.ru.md). Возвращены detail/Back; activity subscription перенесена над вкладками. Новая проверка — 6/6 device tests, реальный first-frame и Events round-trip. Описанный ниже блокер отсутствующей сессии относится к первоначальному прогону, не к последующему smoke.
Основа: [спецификация Frigate Live](frigate-live-protocol.ru.md), локальный source reference Frigate PWA (`LivePlayer`, `LiveDashboardView`). Версия checkout не сопоставлена с deployed server; pixel-perfect parity с работающей PWA не заявляется.

Продолжение: [панель live-камеры — ревью против PWA и зум как свойство плеера](live-camera-panel-review.ru.md) (отдельный экран камеры, зум live и архива).

## Вывод ревью

Главная проблема прежнего экрана — служебные элементы конкурировали с изображением, а разрешение записи, активность и готовность видео смешивались. Правильная иерархия: изображение → объекты/имя/движение → компактная ошибка только при необходимости.

| До | Реализовано |
|---|---|
| Большой FRIGATE / Live header | Компактный Live toolbar |
| Фиксированная высота 220 dp и Crop | Aspect buckets 16:9 / 32:9 / 8:9, Fit |
| REC ON/OFF, Updated, STALE поверх изображения | Без REC/служебных timestamps; отдельный motion-dot |
| Разрозненные object badges | Единый серый chip слева, overflow +N |
| Остановка/старт от recording state | Visibility + enabled/online + motion/non-stationary tracking |
| Snapshot исчезал раньше реального video output | Still скрывается по текущему Surface first-frame + texture update |
| Мгновенная остановка по концу активности | 500 ms grace с отменой при новой активности |
| Общая ошибка камеры | Раздельные disabled, offline, snapshot failure и media error + Retry |

## Компоновка

- Одна колонка на телефоне — default PWA, не дефект; две от 600 dp.
- Gap 8 dp; radius 8 dp compact / 16 dp expanded.
- Белые object icons в сером chip слева сверху; stationary objects остаются видимы.
- Имя справа сверху постоянно: deliberate Android default, соответствующий PWA `alwaysShowCameraName`.
- Красная точка означает motion, не факт записи. Tracking даёт 3 dp orange outline, не review severity.
- Общий clipping картинки/video/overlays. Fit не обрезает края; picture aspect уточняется по decoded video size.
- Retry минимум 48 dp; состояния имеют текст/semantics, не только цвет.

Рендеринг: `app/src/main/java/com/frigate/android/feature/live/LiveCameraTile.kt`.
Оркестрация: `LiveCameraScreen.kt`.

## Поведение и ownership

- Snapshot cache переживает recomposition и scroll. Полезные intervals: idle 30 s, startup 200 ms, ready 60 s, returning 300 ms; hidden/disabled/offline stop.
- Recording OFF не блокирует activity-driven live.
- Player pool сохраняет видимые identities camera/stream/session/retry. Ушедшие из viewport камеры освобождаются; возврат может создавать новый поток.
- `NativeMseVideo` владеет TextureView Surface, pool — player. Точный Surface identity проверяется в `AnalyticsListener`; READY не является first-frame readiness. Texture update нужен дополнительно перед снятием still.
- ON_STOP/уход с Live освобождают media и socket, отменяют snapshot work. Generation gate отвергает запоздалые callbacks.
- Offline определяется stats или snapshot header, не произвольной сетевой ошибкой.
- Native MSE, video-only, buffer policy и безопасное хранение сессии сохранены. WebRTC/WebView fallback не добавлялись.

## Свежие проверки

- Regression RED→GREEN: config retention/dimensions, stationary/enabled/stats, initial stats и redirect rejection, snapshot metadata/height, demand/readiness/grace/polling/aspect, session generation.
- Full JVM suite + `assembleDebug`: BUILD SUCCESSFUL (Java 21).
- `LiveCameraTileTest` на физическом Android: **4/4 passed**, прямой AndroidJUnitRunner, финальный прогон 3.741 s. Дополнительный тест рисует реальный красный кадр в opaque TextureView: зелёный still закрывает его до videoReady, затем кадр виден под name chip и rounded clipping. Screenshot открыт и проверен. Это проверяет interop compositing, не Media3 decoding.
- Исправлена discovery существующего `CameraSnapshotLoaderTest.rejectsNonSuccessResponse`: до изменения targeted run сообщал No tests found, после явного Unit return тест запускается и проходит.
- Получен и открыт screenshot настоящей Compose-плитки с **тестовым**, не камерным изображением: имя, motion-dot, object chip, tracking border и clipping видимы. Это не доказательство MSE handoff.

### Блокер device acceptance

Baseline до изменений показывал Connected и реальные snapshots. После раннего Gradle connected instrumentation запуска и последующей установки приложение оказалось на экране настройки без сохранённого сервера. Вероятная причина — uninstall target APK в cleanup Gradle connected tests; причинность не подтверждена отдельным trace. Явных команд clear-data или uninstall не выполнялось. Данные/настройки далее не восстанавливались и не изменялись автоматически.

Первый чёрный screenshot сделан во время перехода launch; повторный settled screenshot при Awake, MainActivity focus и PID 6856 показал onboarding. Пустой filtered MSE log не доказывает ни обрыв, ни успешный playback: подключённой камеры в этом запуске нет.

**Не подтверждены на новом экране:** реальный first-frame handoff, sustained multi-camera playback, release при scroll/Events/background, offline recovery, DIM, landscape и увеличенный шрифт. Для завершения acceptance требуется снова подключить сервер через UI и выполнить матрицу из [стратегии тестирования](testing-strategy.ru.md). Уменьшение прежних 5–10 s startup и исправление потери событий при DIM не заявляются.

### Безопасный повтор проверки

На пользовательском устройстве не повторять `connectedDebugAndroidTest` без разрешения на uninstall/cleanup. Использовать install-in-place и прямой runner; Gradle connected workflow оставить выделенному test target. Logcat не очищать.

## Принятые решения

1. Работа in-place, без commits/worktree: каталог не является Git repository. Цена — изменения не имеют commit history; отчёт и тесты остаются записью результата.
2. Explicit Surface + TextureView вместо XML PlayerView: позволяет проверить ownership по actual output. Цена — собственный маленький texture host и обязательная физическая проверка compositing.
3. Reference — source checkout, не screenshot deployed PWA. Цена — визуальная/версионная parity с конкретным сервером не подтверждена.
4. Полный connected instrumentation повтор не выполняется на пользовательском target из-за риска удаления данных; прямой runner прошёл. Цена — этот Gradle gate остаётся неподтверждённым.

## Независимое ревью

Выполнено одно независимое read-only ревью и один fix pass.

- **F1, TextureView якобы поверх всех Compose overlays:** не подтвердилось. Физический `textureOutputStaysUnderStillAndOverlays` прошёл без изменения production renderer: центр зелёный при still, красный после videoReady, name chip выше texture, corners clipped. Предложенные alpha/SurfaceView изменения не внесены. Цена решения — другие устройства и реальный decoder по-прежнему требуют acceptance.
- **F2, повторный prepare наследует readiness:** заявленный путь не подтвердился по callers. Единственный production caller вызывает prepare после создания нового Surface и `attachOutput`, который сбрасывает readiness; error callback также сообщает false. Retry создаёт новый player. Изменение не внесено. Цена — если появится prepare на том же binding, контракт нужно пересмотреть.
- **F3, HTTP failure снимает cached offline:** заявленный пользовательский сценарий не подтверждён. Header offline переводит interval в stop и отменяет запросы; следующий failed poll без override stats не запускается. При stats online runtime уже является приоритетным достоверным источником. Изменение не внесено. Цена — при изменении offline polling необходимо сохранять metadata явно.
- **F4, mode change отменяет in-flight image:** подтверждено и исправлено. `modeChangeDoesNotCancelInFlightSnapshot` сначала падал по cancelled request, затем прошёл. `pollCameraSnapshots` меняет только timer; refresh остаётся последовательным и не отменяется на 200↔30000↔60000 ms. Hidden/disabled/offline/STOP всё ещё отменяют работу. Полный JVM suite и build после fix прошли.
- **F5, последнее activity до reconnect snapshot:** сохранено намеренно, как PWA. Generation gate исключает старые callbacks, новый snapshot восстанавливает состояние. Цена — во время разрыва UI временно показывает последнее известное activity.
- **F6, широкая recomposition при snapshot writes:** minor, отложено. Screen читает map при построении offline key; equality предотвращает ненужный restart эффекта, но не саму recomposition. Возможная оптимизация — derived offline state; цена — лишняя UI-работа при startup polling.

Критичных подтверждённых дефектов после fix pass не осталось; runtime acceptance остаётся заблокированным. Отложенная minor: F6.

