**[English](2026-10-30-live-review-carousel-design.md)** | Русский

# Live: непросмотренные alerts и просмотр записи

> **SUPERSEDED.** 2026-10-30 пользователь отверг авторский упрощённый bounded-VOD player и принял новый макет History по PWA (`docs/design/live-pwa-history.html`). Действующий контракт — `docs/superpowers/specs/2026-10-30-pwa-history-android-design.md`. Раздел «Просмотр записи: рекомендуемый ограниченный первый этап» ниже — отклонённое предложение, не выполнять.

## Согласовано
Пользователь принял браузерный макет `docs/design/live-unreviewed-carousel.html` 2026-10-30 и выбрал открытие записи при нажатии на событие. Макет — визуальная основа; существующий native MSE Live сохраняется.

## Карусель
- Компактная горизонтальная лента под заголовком Live, перед сеткой камер. Только dashboard; не detail камеры и не экран записи.
- До 10 непросмотренных review segments с severity=alert, начавшихся за последний час; новые слева. Это `/api/review`, не `/api/events`.
- Статичное authenticated-превью, имя камеры, относительное время, объекты, признак продолжающегося события и отдельная touch-кнопка отметки просмотра. Без автоматического перелистывания и autoplay previews.
- Пустой список полностью скрывает блок. Initial loading, ошибка списка с Retry и недоступность конкретного thumbnail различаются. Неуспешное обновление не удаляет уже полученный список.
- Простое отображение/scroll ничего не отмечает. Явная отметка и открытие записи отправляют `POST api/reviews/viewed`, `{"ids":[id],"reviewed":true}`. Удаление из ленты — после подтверждённого успеха; при ошибке показать повтор без ложного успеха. Экран записи может открываться независимо от результата отметки.
- REST — источник списка. Существующий shared `/ws`, topic `reviews`, служит сигналом revalidation через независимый callback до дедупликации camera reducer; дополнительный WebSocket не нужен. end — 1 s; new/genai — 6 s; рост objects — 5 s. События объединяются без бесконечного откладывания более раннего refresh. Reconnect/возврат/вход в Live всегда восстанавливает список через REST. Пока Live видим, периодическая сверка 30 s обновляет относительное время/часовой фильтр и статусы другого клиента.

## Просмотр записи: рекомендуемый ограниченный первый этап
- Отдельный native Media3 VOD-экран с заголовком камеры/события, Back, play/pause, перемоткой в пределах загруженного интервала, buffering и понятной ошибкой/Retry. Это запись, не переключение на live выбранной камеры.
- Начало `max(0, start_time - 4)`; завершённый segment — до `end_time + 4`, не позже текущего времени; продолжающийся — до текущего времени открытия. Для него явный статус «Событие ещё идёт» и действие обновления доступной записи. Автоматически к live не переключаться.
- HLS URL строить из профиля: `vod/{encoded camera}/start/{start}/end/{end}/master.m3u8`, без `api/` перед внешним nginx VOD-путём. Камера — ID review, не go2rtc stream ID.
- Пустая запись/ещё не сохранённые сегменты/403/404/codec error — отдельное понятное состояние, не бесконечный spinner. Retry использует свежий конец для ongoing.
- При уходе с dashboard останавливаются Live media и snapshot polling, shared activity остаётся. При уходе с записи/Activity STOP player и сетевые операции освобождаются. Возврат в START восстанавливает позицию/состояние play-pause в новой сессии player; Back восстанавливает dashboard и позицию сетки.
- Звук записи по умолчанию выключен. Audio/PTZ/full archive timeline/export/fullscreen не добавляются этой задачей.

## Безопасность и совместимость
- Android 12+, Kotlin/Compose/Media3 1.9.0; без WebView и новой library family. Допустимы модули текущего Media3 `exoplayer-hls` и `datasource-okhttp` той же версии.
- Cookies только в заголовках запросов своего origin. Проверять origin каждого manifest/segment/key/thumbnail запроса до отправки; redirects запрещены, в том числе между одинаковыми протоколами разных hosts. Не полагаться на `allowCrossProtocolRedirects=false` как на same-origin защиту.
- Thumbnail `thumb_path` — недоверенный серверный путь: принимать только ожидаемый `/media/frigate/clips/review/…`, преобразуя в profile-relative `clips/review/…`; запретить absolute URL, traversal и неизвестные пути. Отсутствующий/неподдерживаемый путь даёт fallback, не загрузку с чужого адреса.
- Session generation защищает от поздних ответов прежнего профиля/cookie. Кэш thumbnails, pending writes и player не переходят между сессиями.
- Touch targets >=48 dp, descriptions, читаемость при крупном шрифте; цвет не единственный признак состояния. Лента участвует в вертикальном scroll сетки; её item key не должен попадать в camera visibility как камера.
- Не менять MSE negotiation/buffering, motion/tracking gates или доступность камер. Без Wi-Fi routing.

## Источники и ограничения
PWA source reference: `web/src/views/live/LiveDashboardView.tsx`, `components/card/AnimatedEventCard.tsx`, `components/player/dynamic/DynamicVideoPlayer.tsx`, `types/review.ts`; backend `frigate/api/review.py`, `frigate/api/media.py`, nginx `/vod/` и `/clips/`. Checkout находится в `<upstream-checkout>/frigate`; совпадение с deployed server не установлено.

Ограниченный интервал VOD и muted default — предлагаемая реализация первого этапа, не утверждение о полном совпадении с архивным интерфейсом PWA. Физический runtime обязателен для доказательства playback; unit/build этого не доказывают. Серверные viewed writes при smoke меняют пользовательский статус: выполнять только для явно разрешённого тестового segment, иначе проверять запись без этой mutation и отдельно отметить ограничение.
