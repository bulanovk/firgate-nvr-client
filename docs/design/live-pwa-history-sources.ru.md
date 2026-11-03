**[English](live-pwa-history-sources.md)** | Русский

# History: основание браузерного макета

Статус: новый вариант после отклонения упрощённого player; ожидает согласования. Android-код не изменён. Принятый `live-unreviewed-carousel.html` сохранён отдельно.

Макет: [live-pwa-history.html](live-pwa-history.html).

## Изученный источник

Frigate upstream checkout: `<upstream-checkout>/frigate`. Указанные ниже пути относительно этого checkout. Совпадение с версией развёрнутого у пользователя PWA не установлено. Код checkout не запускался.

| Сценарий | Источник | Отражение в макете |
| --- | --- | --- |
| Нажатие Live review | `web/src/components/card/AnimatedEventCard.tsx`, `web/src/types/review.ts` | Камера события, начало за 4 s; локальная отметка просмотренным при открытии. Не bounded clip. |
| Диапазон History | `web/src/pages/Events.tsx`, `web/src/views/recording/RecordingView.tsx` | При обычном входе последние 24 часа; отдельный выбор дня. Конец review не завершает воспроизведение архива. |
| Мобильная компоновка | `web/src/views/recording/RecordingView.tsx` | Back/Live слева, логотип по центру, камера/панель/настройки справа. Видео Fit; панель ниже в portrait, справа в landscape. |
| Управление видео | `web/src/components/player/HlsVideoPlayer.tsx`, `web/src/components/player/VideoControls.tsx` | Плавающая панель поверх кадра: звук, −10, play/pause, +10, скорость, снимок, fullscreen. Нет собственного горизонтального clip slider и строки «Готово». |
| Камеры и режимы | `web/src/components/overlay/MobileCameraDrawer.tsx`, `web/src/components/overlay/MobileTimelineDrawer.tsx` | Bottom drawers; смена камеры сохраняет timestamp. Хронология / События / Деталь. |
| Архивная шкала | `web/src/components/timeline/MotionReviewTimeline.tsx`, `web/src/components/timeline/MotionSegment.tsx`, `web/src/components/timeline/ReviewTimeline.tsx` | Новое сверху; вертикальная шкала, motion/severity, пробелы записи, перетаскиваемая красная позиция, масштаб 30/15/5 s на сегмент. |
| Настройки и фильтр | `web/src/components/overlay/MobileReviewSettingsDrawer.tsx`, `web/src/components/filter/ReviewFilterGroup.tsx` | Действия одной колонкой. Фильтр меток, а не второй выбор камеры; apply/reset, нельзя снять последнюю выбранную метку. Синтетическая конфигурация без зон, sub-recording quality и admin/Frigate+. |
| Events и Detail | `web/src/components/card/ReviewCard.tsx`, `web/src/components/timeline/DetailStream.tsx` | События камеры за диапазон и раскрываемые группы с lifecycle seek. |

Имена файлов в checkout являются ссылками на исследование, не гарантией pixel-perfect соответствия. Русские названия режимов сверены с `web/public/locales/ru/views/events.json`.

## Расхождение по списку скоростей

Панель управления в макете взята из `VideoControls.tsx` — это общий компонент, но в самом PWA он живёт только в live-плеере (`HlsVideoPlayer.tsx`). Список скоростей в нём по умолчанию `PLAYBACK_RATE_DEFAULT = [0.5, 1, 2, 4, 8, 16]`, его и повторяет макет.

Собственное экранное представление записи в PWA устроено иначе: `Events.tsx` рендерит `RecordingView`, а тот работает с `DynamicVideoPlayer`, где панели `VideoControls` и выбора скорости нет вообще. Второе представление события — `web/src/views/events/EventView.tsx` — имеет свой Rate-переключатель, но предлагает `[4, 8, 12, 16]` и стартует на 8× (`useState(8)`), а список скоростей у него переопределён в вызове `VideoControls`.

Итог: макет повторяет общий компонент PWA, а не уникальный набор скоростей экранного представления записи. Это зафиксированное расхождение, а не ошибка сборки; см. [верификацию карусели и History](../live-review-history-verification.ru.md).

## Границы проверки

Все кадры и записи синтетические. Воспроизведение — локальный таймер, не HLS. Нет реального аудио, запросов Frigate, viewed writes, скачивания или экспорта. Export, Share, Motion Search и Snapshot открывают честные пояснения, а не работающие формы PWA. Calendar — упрощённый date input, не точная копия календаря. Боксы объектов и все сценарии Detail не реализованы.

Проверено: JS syntax; 13 controller/structure checks; 7 smoke-сценариев полного скрипта на DOM test double. Это не настоящий браузер и не проверка layout/rendering, HLS или Android runtime. Физическое устройство и production-сборка не использовались.

Регрессии диапазона и фильтра сначала воспроизведены RED, после изменения GREEN. Скрипт генерации в job temp — исторический черновик: финальный HTML редактировался после генерации; не перегенерировать им поверх макета.
