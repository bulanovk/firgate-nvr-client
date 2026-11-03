**[English](frigate-live-protocol.md)** | Русский

# Frigate PWA: спецификация API вкладки «Прямой эфир»

## 1. Назначение и границы

Документ описывает сетевой контракт и правила отображения вкладки PWA «Прямой эфир»: список камер, still-изображения, активность и объекты, запуск live, переход still ↔ video, восстановление соединений и возврат из background.

Спецификация восстановлена по исходникам Frigate и go2rtc, включая вызывающие плееры dashboard/grid и экран выбранной камеры. Это описание существующего поведения, не предложение нового API. Дата исследования: 2026-10-29. Исследованный checkout не сопоставлен с версией установленного сервера; различия между релизами возможны. Источники — в приложении A.

Область: просмотр камер и лента последних alerts. Управление PTZ, изменение конфигурации, двухсторонняя связь и архив не входят в документ.

**Состояние камеры, still и live-видео — независимые каналы.** Сообщение об объекте не содержит видео; открытый media socket не означает готовность первого кадра.

## 2. Адресация и авторизация

Обозначения:

- `BASE` — внешний HTTP(S) URL Frigate с завершающим `/`, включая deployment prefix;
- `WS_BASE` — тот же URL с заменой `https` → `wss`, `http` → `ws`;
- `camera` — техническое имя камеры, не `friendly_name`;
- `stream` — значение из `cameras[camera].live.streams`, не обязательно имя камеры.

Например, при `BASE = https://nvr.example/frigate/` конфигурация доступна по `https://nvr.example/frigate/api/config`, состояние — по `wss://nvr.example/frigate/ws`.

PWA использует сессию текущего origin. Браузер передаёт подходящие session cookies в HTTP и WebSocket upgrade. Логин/пароль и cookie не являются query-параметрами media URL. На защищённом сервере до загрузки Live нужна действительная сессия. Backend также поддерживает Bearer-авторизацию, но браузерный WebSocket PWA не устанавливает произвольный заголовок `Authorization`.

Сервер проверяет права на камеры и WS-топики. Подключение к `/ws` не предоставляет доступ ко всем камерам. Отказ авторизации — не пустой snapshot и не `motion=OFF`.

### 2.1. Карта API

Пути относительно `BASE` или `WS_BASE`:

| Назначение | Транспорт | Endpoint |
|---|---|---|
| Камеры, конфигурация, stream mapping | HTTP GET | `api/config` |
| Последний still | HTTP GET | `api/{camera}/latest.webp` |
| Начальная статистика, включая offline | HTTP GET | `api/stats` |
| Runtime-состояние, motion, объекты | WebSocket | `ws` |
| Метаданные restream | HTTP GET | `api/go2rtc/streams/{stream}` |
| MSE live | WebSocket | `live/mse/api/ws?src={stream}` |
| WebRTC signaling | WebSocket | `live/webrtc/api/ws?src={stream}` |
| JSMpeg live | WebSocket | `live/jsmpeg/{camera}` |
| Последние непросмотренные alerts | HTTP GET | `api/review?limit=10&severity=alert&reviewed=0&cameras={camera-list}` |

При построении URL path segments и query values необходимо корректно кодировать. Нельзя без проверки подставлять `camera` вместо `stream`.

## 3. Конфигурация и выбор потока

### 3.1. `GET api/config`

Ответ: `200`, JSON. Ниже — **проекция полей для Live**, не полный ответ конфигурации; значения условные:

```json
{
  "cameras": {
    "entrance": {
      "name": "entrance",
      "friendly_name": "Вход",
      "enabled": true,
      "detect": {"enabled": true, "width": 1280, "height": 720},
      "record": {"enabled": true},
      "live": {
        "streams": {
          "Основной": "entrance_main",
          "Обзор": "entrance_sub"
        }
      }
    }
  },
  "go2rtc": {
    "streams": {"entrance_main": [], "entrance_sub": []},
    "webrtc": {"ice_servers": []}
  },
  "camera_groups": {},
  "birdseye": {"enabled": false, "restream": false}
}
```

Правила:

1. Ключ камеры/`name` — идентификатор для API и WS. `friendly_name` — подпись.
2. `live.streams` — отображение **подпись → stream ID**. Snapshot и object events адресуются по камере; MSE/WebRTC — по stream ID.
3. Dashboard выбирает сохранённый для камеры/группы stream, если он присутствует среди значений `live.streams`. Иначе — первое значение либо пустую строку при отсутствии streams.
4. Группа и права пользователя определяют состав dashboard. Пользовательские streaming preferences не заменяют конфигурацию сервера.
5. `record.enabled` — разрешение записи, не текущая активность и не условие автоматического запуска live.
6. Runtime feature states уточняются через `/ws`. Нельзя повторно применять старую конфигурацию поверх каждого нового object event.

### 3.2. Метаданные restream

```http
GET api/go2rtc/streams/entrance_main
```

Ответ — JSON-описание go2rtc stream, включая producers/consumers и media/codecs. PWA использует метаданные для определения доступного audio и возможностей выбранного stream. Это не видеоданные и не сигнал активности. Dashboard загружает метаданные отложенно, не блокируя ими первый still.

## 4. Still-изображения

### 4.1. Запрос

```http
GET api/entrance/latest.webp?height=360&cache=1793221200123
```

| Параметр | Тип | Семантика |
|---|---|---|
| `height` | integer | Высота изображения; ширина сохраняет пропорции |
| `cache` | значение в URL | Cache-buster PWA, не время искомого кадра и не курсор истории |
| `store` | integer | При `1` разрешает private HTTP cache на 60 s; без него ответ `no-store` |
| `quality` | integer | Качество кодирования; backend default `70`, обычная Live-плитка не задаёт |

Backend также принимает `bbox`, `timestamp`, `zones`, `mask`, `motion`, `paths`, `regions` для диагностических слоёв. Обычный still в Live их не запрашивает.

PWA выбирает высоту не больше `detect.height`: примерно высота контейнера × 1.1 на desktop или × 1.25 на mobile; до измерения — `360`.

При включённом periodic cache первый запрос содержит `store=1` и `cache`, округлённый до 10-минутного интервала Unix time в секундах. Последующие обновления используют текущий `Date.now()` в миллисекундах. Эти значения не задают время видеокадра.

### 4.2. Ответ

Успех: `200`, `Content-Type: image/webp`, бинарное изображение.

- По умолчанию `Cache-Control: no-store`.
- При `store=1`: `Cache-Control: private, max-age=60`.
- При offline backend может вернуть последний preview с `X-Frigate-Offline: true`.
- Неизвестная камера: `404`, JSON `{"success":false,"message":"Camera not found"}`.
- Невалидные вычисленные размеры: `400`; невозможность получить пригодный кадр: `500`, JSON `{"success":false,"message":"Unable to get valid frame"}`.
- HTTP 200 и наличие картинки не доказывают online: возможен fallback к preview/изображению ошибки.

Still — текущий кадр пути обработки камеры, не snapshot конкретного object event и не кадр выбранного go2rtc stream. Пропорции и разрешение still/live могут различаться.

### 4.3. Частота обновления dashboard

Применяется первое подходящее правило сверху вниз:

| Условие | Интервал, ms |
|---|---:|
| Плитка/окно невидимы, камера offline либо режим без still | `-1`: остановить обновления |
| Live готов, активность уже закончилась | `300` |
| Live готов | `60000` |
| Есть motion/tracking, auto-live включён, live ещё не готов | `200` |
| Есть motion/tracking, auto-live выключен | `59000` |
| Остальные случаи | `30000` |

Это клиентский polling, не серверный FPS. После успешной загрузки PWA ждёт ещё `interval` ms; если сама загрузка заняла больше `interval`, следующий запрос назначается через `1` ms. Поэтому обычный период между началами запросов включает и время загрузки, и задержку после неё. При ошибке повтор для работающего цикла — не чаще `max(interval, 1000)` ms. `-1` отключает повторное обновление, а не задаёт отрицательную задержку.

## 5. WebSocket состояния Frigate

### 5.1. Соединение и envelope

```text
WS_BASE + ws
```

Один shared WebSocket обслуживает runtime-состояние камер. Отдельной команды подписки на каждую камеру PWA не отправляет.

Клиент → сервер, bootstrap:

```json
{"topic":"onConnect","message":"","retain":false}
```

Сервер → клиент:

```json
{"topic":"entrance/motion","payload":"ON"}
```

| Поле | Направление | Назначение |
|---|---|---|
| `topic` | оба | Имя команды/канала |
| `message` | клиент → сервер | Поле исходящего envelope PWA; для `onConnect` пустая строка |
| `retain` | клиент → сервер | Флаг envelope, не запрос replay истории WS |
| `payload` | сервер → клиент | Значение topic; сложные сообщения обычно содержат строку JSON |

`onConnect` обрабатывается по имени topic, его значение не используется. Показанный исходящий envelope не следует автоматически переносить на любые другие команды управления.

**Реальный text frame** со сложным payload имеет два уровня JSON:

```json
{"topic":"camera_activity","payload":"{\"entrance\":{\"motion\":false,\"objects\":[],\"config\":{\"enabled\":true,\"record\":true,\"detect\":true}}}"}
```

Сначала разбирается envelope, затем JSON внутри `payload`. Далее примеры сложных сообщений показывают **раскодированный payload**, без внешнего envelope.

### 5.2. `onConnect`: запрос текущего состояния

Открытие socket само по себе не запрашивает snapshot. После `open` PWA отправляет `onConnect`.

Сервер публикует runtime snapshot, включая `camera_activity`; также могут прийти `audio_detections`, `birdseye_layout`, `model_state`, `profile/state` и другие служебные topics. Это несколько сообщений, не единый атомарный ответ.

Свойства:

- Нет request ID и отдельного ACK.
- Snapshot рассылается подключённым клиентам с фильтрацией прав; его может инициировать другая вкладка/клиент.
- Нет sequence number, revision boundary, cursor и replay пропущенных events.
- Snapshot и параллельные events не образуют транзакцию.
- Восстанавливается **текущее состояние**, не история за время отключения.

### 5.3. `camera_activity`: snapshot камер

Раскодированный payload:

```json
{
  "entrance": {
    "motion": true,
    "objects": [
      {
        "id": "1793221200.123456-ab12cd",
        "label": "person",
        "stationary": false,
        "area": 32000,
        "ratio": 0.45,
        "score": 0.87,
        "sub_label": ""
      }
    ],
    "audio_detections": [],
    "config": {
      "enabled": true,
      "detect": true,
      "snapshots": true,
      "record": true,
      "audio": false,
      "audio_transcription": false,
      "notifications": false,
      "notifications_suspended": 0,
      "autotracking": false,
      "alerts": true,
      "detections": true,
      "object_descriptions": false,
      "review_descriptions": false
    }
  }
}
```

| Поле | Тип | Значение |
|---|---|---|
| Верхний ключ | string | Имя камеры |
| `motion` | boolean | Motion в snapshot, не исторический интервал |
| `objects` | array | Текущее представление отслеживаемых объектов |
| `objects[].id` | string | Идентичность объекта внутри камеры |
| `objects[].label` | string | Класс/метка |
| `objects[].stationary` | boolean | Объект считается неподвижным |
| `objects[].area`, `ratio`, `score` | number | Атрибуты объекта, не условия запуска live |
| `objects[].sub_label` | string | Дополнительная отображаемая метка snapshot |
| `config.*` | boolean, кроме следующей строки | Runtime-состояния функций |
| `config.notifications_suspended` | number | Значение приостановки уведомлений, не boolean |
| `audio_detections` | array | Audio detections; отдельный audio topic также обновляет их |

В исследованном backend runtime snapshot собирается по всем настроенным камерам и ограничивается правами получателя. Это **не документированный поток частичных per-frame patches**. Камера без накопленных наблюдений может содержать только `config`, без `motion`/`objects`.

Применение в PWA:

1. Развернуть snapshot в локальные состояния `camera_activity/{camera}` и feature state topics.
2. Для присутствующей камеры заменить objects на `objects` либо `[]`, если поле отсутствует. Не объединять со старым списком объектов.
3. Пустой верхнеуровневый объект игнорируется. Отсутствие камеры в map не трактуется как команда завершения всех её объектов.
4. Повторный snapshot согласует состояние, даже если совпадает с прежним: между ними objects могли измениться через `events`.
5. Snapshot применяется по факту получения, не при каждом последующем рендере UI.

### 5.4. `events`: изменения отслеживаемых объектов

Раскодированный payload, проекция полей для Live:

```json
{
  "type": "update",
  "before": {
    "id": "1793221200.123456-ab12cd",
    "camera": "entrance",
    "label": "person",
    "stationary": false
  },
  "after": {
    "id": "1793221200.123456-ab12cd",
    "camera": "entrance",
    "label": "person",
    "stationary": true,
    "sub_label": null,
    "area": 32000,
    "ratio": 0.45,
    "score": 0.87
  }
}
```

- `type`: `new`, `update`, `end` в исследованном publisher. `start` не является его штатным типом.
- `before`: предыдущее опубликованное представление объекта, не обязательно предыдущий видеокадр.
- `after`: новое представление; полный event содержит дополнительные поля времени, geometry, зон, media flags и recognition.
- `sub_label` в event — `null` либо массив с меткой и confidence. Это **не тот же тип**, что строка `sub_label` в отображаемом snapshot.

Правила:

1. Маршрутизировать по `after.camera`, искать объект по `after.id`, не по `label`.
2. `end` удаляет только этот объект; неизвестный ID ничего не удаляет.
3. Для неизвестного не-stationary объекта `new`/`update` добавляет объект. Неизвестный stationary объект PWA не добавляет.
4. У известного объекта обновить отображаемую метку и `stationary`. Сохранить остальные ID и камеры.
5. При `sub_label` PWA использует его как label, если это известный attribute label; иначе отображает verified-вариант исходного класса. Дополнительная метка берётся из первого элемента `sub_label`.
6. `activeTracking = есть хотя бы один объект с stationary=false`. Stationary объекты могут оставаться в списке, но не включают tracking.

Не каждый кадр порождает `update`. Topic не heartbeat и не надёжный журнал всех переходов. После disconnect требуется новый snapshot.

### 5.5. Motion и состояния функций

Полный envelope, без второго JSON parse:

```json
{"topic":"entrance/motion","payload":"ON"}
```

| Topic | Payload | Семантика |
|---|---|---|
| `{camera}/motion` | `"ON"` / `"OFF"` | Активность движения |
| `{camera}/enabled/state` | `"ON"` / `"OFF"` | Камера включена/выключена |
| `{camera}/detect/state` | `"ON"` / `"OFF"` | Детекция разрешена/выключена |
| `{camera}/recordings/state` | `"ON"` / `"OFF"` | Запись разрешена/выключена |

Motion topic публикует изменения, не периодический heartbeat. `OFF` может задерживаться согласно `motion.mqtt_off_delay`. Snapshot motion отражает мгновенное состояние обработки и может отличаться от delayed OFF topic.

Для `activeMotion` PWA использует полученный motion topic; при его отсутствии — `camera_activity.motion === true`. Выключенная камера подавляет отображаемые motion/tracking/objects.

`{camera}/motion` и `{camera}/motion/state` — разные каналы: активность и настройка функции. `recordings/state=ON` не означает «сегмент записи прямо сейчас пишется».

### 5.6. Статистика и audio

Начальная статистика: `GET api/stats`. Последующие обновления — topic `stats`, JSON payload. Для offline существенна проекция:

```json
{"cameras":{"entrance":{"camera_fps":0}},"service":{"uptime":125}}
```

Offline в PWA: `camera_fps == 0` и `service.uptime > 60` секунд. До получения stats отсутствие данных само по себе не объявляет камеру offline.

Topic `audio_detections` содержит JSON map `camera → detections`, элементы имеют `id`, `label`, `score`. Audio обновляется отдельно. Формула auto-live использует motion/tracking, не наличие audio detections.

## 6. Правила отображения

### 6.1. Dashboard и выбранная камера

| Режим | Auto-live | Still без активности | Поведение |
|---|---|---|---|
| Dashboard по умолчанию | Включён по умолчанию, можно отключить | Да | Live при motion/tracking видимой камеры |
| `no-streaming` | Нет | Да | Still polling, без media player |
| `continuous` | Да | Нет | Live независимо от motion/tracking |
| Выбранная камера | После загрузки preferences | Нет | Непрерывный live выбранного stream |

На dashboard `windowVisible` учитывает видимость документа **и** попадание камеры в видимую область. Это клиентская политика, не серверный topic. На экране выбранной камеры activity-gate отключён; background playback задаётся отдельно.

### 6.2. Формула запуска

```text
cameraActive = !showStillWithoutActivity
               OR (windowVisible AND (activeMotion OR activeTracking))

playerExists = autoLive AND streamName непустой AND cameraEnabled
playbackEnabled (MSE/WebRTC) = cameraActive OR liveReady
```

`record.enabled`/`recordings/state` в формуле отсутствуют. Отключение записи не равно отключению live. Окончание одного объекта не выключает остальные камеры или другой объект той же камеры.

### 6.3. Переход still → video

Обычная activity-driven плитка:

```text
STILL → STARTING_LIVE → LIVE → RETURNING_TO_STILL → STILL
```

| Состояние | Отображение и действие |
|---|---|
| STILL | Still видим и периодически обновляется; media не воспроизводится |
| STARTING_LIVE | Media запускается; still остаётся видимым и обновляется с интервалом 200 ms |
| LIVE | Callback готовности задаёт `liveReady=true`; video показывается, still скрывается |
| RETURNING_TO_STILL | При исчезновении активности live сохраняется до 500 ms, still обновляется с интервалом 300 ms; затем `liveReady=false`, playback выключается |

При возврате активности в течение 500 ms отложенный сброс отменяется. Это задержка **остановки**, не старта.

Для MSE callback готовности вызывается по `loadeddata`; есть fallback по progress при более 3 s буфера. Открытый socket или полученный MIME ещё не означают готовое видимое видео.

При смене stream MSE/WebRTC player создаётся заново; `liveReady` сбрасывается, новый video скрыт до готовности. JSMpeg привязан к камере, не к go2rtc stream, поэтому смена stream ID его не переключает.

Бесшовный still-overlay относится к activity-driven still-режиму. На экране выбранной камеры `showStillWithoutActivity=false`; такой overlay там не гарантирован.

### 6.4. Индикаторы

- Motion marker отражает `activeMotion`, не состояние media socket.
- Object icons/labels строятся из текущих objects; одинаковые labels не означают один ID.
- Disabled и offline — самостоятельные состояния с overlays.
- Разрешение записи не подтверждает текущую запись файла. Нельзя выдавать motion/tracking за достоверный сигнал записываемого сегмента.
- Playback, motion, tracking, разрешение записи и доступность камеры хранятся независимо.

## 7. MSE: go2rtc media WebSocket

### 7.1. Endpoint

```text
WS_BASE + live/mse/api/ws?src=entrance_main
```

Отдельное от `/ws` соединение для stream. Frigate проксирует его к go2rtc. Здесь нет `topic`, `payload` и `onConnect`.

### 7.2. Negotiation

Клиент → сервер, text frame:

```json
{"type":"mse","value":"avc1.640029,hvc1.1.6.L153.B0,mp4a.40.2,opus"}
```

`value` — предлагаемые codecs через запятую. PWA проверяет browser support и предлагает подходящие варианты из: `avc1.640029`, `avc1.64002A`, `avc1.640033`, `hvc1.1.6.L153.B0`, `mp4a.40.2`, `mp4a.40.5`, `flac`, `opus`.

Сервер → клиент, text frame, пример:

```json
{"type":"mse","value":"video/mp4; codecs=\"avc1.640029,mp4a.40.2\""}
```

Ответ содержит MIME выбранного потока, не эхо offer. Клиент создаёт `SourceBuffer` с этим MIME. PWA допускает audio; контракт не ограничен video-only реализацией другого клиента.

Codec parser исследованного go2rtc отдельно распознаёт `avc1.640029`, `hvc1.1.6.L153.B0`, `mp4a.40.2`, `flac`, `opus`. Поэтому список кандидатов PWA не равен перечню независимо поддерживаемых сервером профилей; добавление token в offer само по себе не гарантирует negotiation или transcoding.

При ошибке go2rtc использует text message типа `error` с описанием в `value`; возможны также ошибка upgrade и закрытие socket. JSON-ошибку нельзя подавать MP4-декодеру.

### 7.3. Binary media

После MIME приходят binary frames с MP4-байтами:

1. Initialization segment с описанием tracks/codecs.
2. Последовательность fragmented MP4 media (`moof`/`mdat`).

Данные подаются декодеру в порядке получения. Это не JPEG/WebP и не Frigate envelope. При занятом `SourceBuffer` PWA использует очередь до 2 MiB — лимит клиента, не размер segment по протоколу.

Новый video consumer ждёт подходящего keyframe. Init/MIME не гарантируют немедленно декодируемый кадр. Протокол не содержит обязательного ожидания 5–10 s и не гарантирует одинаковое время запуска всех камер. Оно зависит от источника, keyframes, доставки, буфера и декодера.

### 7.4. Lifecycle PWA

- При открытии создаётся свежий MediaSource и отправляется новый offer.
- `playbackEnabled=false` закрывает media-соединение.
- Hidden document отключает MSE, если background playback не разрешён; при возврате выполняется новое подключение.
- При сохраняющемся запросе playback player использует reconnect: базовый интервал 10 s отсчитывается от начала предыдущего подключения, поэтому после позднего разрыва повтор может быть немедленным; явно заданный retry timeout переопределяет расчёт.
- Startup/fallback deadline определяется пользовательским `liveFallbackTimeout`, default 3 s. Это дедлайн реакции на проблему, не обязательная задержка воспроизведения.
- Сохраняемый buffer ограничивается примерно 15 s; PWA догоняет live edge, в том числе скоростью до 2×. Детали отличаются для Safari.
- Закрытие media socket не останавливает запись и не завершает object events.

## 8. Выбор транспорта и альтернативы

### 8.1. Выбор dashboard

1. Restreamed stream + browser MSE → MSE.
2. Без MSE, если restream и WebRTC доступны → WebRTC.
3. Иначе → JSMpeg.
4. Сохранённый выбор пользователя применяется, если транспорт доступен.

При `mse-decode`/`mse-codec` dashboard переключается на WebRTC, если он доступен. В остальных обработанных ошибках — на JSMpeg. Завершение activity может сбросить preferred mode к исходному выбору.

Нельзя считать, что PWA всегда использует MSE во всех браузерах и настройках.

### 8.2. WebRTC

```text
WS_BASE + live/webrtc/api/ws?src=entrance_main
```

JSON text signaling, без Frigate topic envelope:

| Направление | `type` | `value` |
|---|---|---|
| Клиент → сервер | `webrtc/offer` | SDP offer строкой |
| Сервер → клиент | `webrtc/answer` | SDP answer строкой |
| Оба | `webrtc/candidate` | ICE candidate строкой |

Клиент создаёт PeerConnection, recvonly video/audio transceivers, local offer и передаёт SDP. Answer задаёт remote description, candidates добавляются в ICE. Настройки `go2rtc.webrtc.ice_servers` участвуют в построении PeerConnection.

Видео идёт по WebRTC media-транспорту, не binary MP4 через signaling socket. SDP answer не доказывает установку ICE или декодирование кадра.

### 8.3. JSMpeg

```text
WS_BASE + live/jsmpeg/entrance
```

Выбирается **камера**, не значение `live.streams`. По socket приходит бинарный MPEG-TS/MPEG-1 video для JSMpeg decoder. MSE codec offer отсутствует. Это альтернативный путь, не тот же H.264/H.265 fMP4 с другим URL.

### 8.4. Birdseye

Составной вид, не обычная камера с object map. Dashboard выбирает MSE при `birdseye.restream=true`, иначе JSMpeg; ID источника — `birdseye`. Нельзя запускать его только по условию `events.after.camera == birdseye`.

## 9. Bootstrap, reconnect и возврат

### 9.1. Последовательность

```text
1. Действительная сессия нужного origin.
2. GET api/config → камеры, подписи, stream IDs.
3. Открыть /ws → при open отправить onConnect.
4. camera_activity → согласовать objects и feature states.
5. GET api/stats → начальная доступность; далее stats topic.
6. latest.webp видимых still-плиток.
7. Применять motion/events/feature states независимо от images.
8. При необходимости live → отдельный media socket камеры/stream.
9. До готовности live сохранить still там, где включён still-режим.
```

HTTP, images и открытие `/ws` могут выполняться параллельно. Нумерация показывает зависимости, не требование сериализовать все запросы.

### 9.2. Восстановление

После закрытия shared `/ws` PWA использует exponential reconnect: примерно 1, 2, 4, 8, 16, затем до 30 s между попытками. После `open` повторно отправляет `onConnect`, инвалидируя cache дедупликации camera activity.

При document visibility → `visible` camera state hooks снова запрашивают `onConnect`; одновременные запросы объединяются. Это нужно и без явного disconnect: браузер мог приостановить страницу.

Повторный snapshot нельзя отбрасывать только потому, что он совпадает с прежним: между ними objects могли измениться через `events`.

WS replay отсутствует. Пропущенные `new`/`end` нельзя запросить по offset. Новый snapshot заменяет текущее представление объектов присутствующих камер. Исторические alerts читаются через REST review, не через replay object events.

### 9.3. Гарантии и ограничения

- Сообщения одного действующего socket упорядочены транспортом.
- Общего порядка между `/ws`, HTTP still и media sockets нет.
- Нет ACK обработки каждого event клиентом.
- Медленный клиент может быть отключён при переполнении очереди/таймауте отправки.
- Нет атомарного snapshot+cursor: exactly-once и gap-free recovery не гарантируются.
- Отсутствие новых motion messages не доказывает обрыв: это edge topic.
- Наличие старого still не доказывает актуальность runtime-состояния.

## 10. Лента alerts dashboard

Это отдельный от активности плиток контракт:

```http
GET api/review?limit=10&severity=alert&reviewed=0&cameras=entrance,yard
```

`cameras` — список технических имён через запятую с учётом группы/прав.

Ответ — массив review segments; проекция:

```json
[
  {
    "id": "1793221200.123456-review",
    "camera": "entrance",
    "severity": "alert",
    "start_time": 1793221200.123456,
    "end_time": null,
    "thumb_path": "<server thumbnail path>",
    "has_been_reviewed": false,
    "data": {"objects": ["person"]}
  }
]
```

Времена — Unix seconds; ongoing segment не имеет конечного времени. Полный объект содержит дополнительные review data.

Обновления приходят по topic `reviews`, **не** `events`. Payload имеет `type`, `before`, `after`, где `after` — review segment. Dashboard повторно запрашивает REST, не считая WS исчерпывающей копией списка:

| Изменение alert | Задержка revalidation |
|---|---:|
| `end` | 1 s |
| `new`/`genai` | 6 s |
| Рост количества objects | 5 s |

Это задержки обновления ленты, не старта live. Object event, review segment и запись файла — разные сущности.

## 11. Контрольные последовательности

### 11.1. Два объекта на одной камере

```text
camera_activity: entrance.objects=[]; motion=false
entrance/motion: ON
  → видимая activity-driven плитка запускает live; still остаётся
events: new, entrance/person-A, stationary=false
  → добавить A; activeTracking=true
media: MIME → init → fragments → browser playback-ready
  → показать video
events: new, entrance/car-B, stationary=false
events: end, entrance/person-A
  → удалить только A; B сохраняет activeTracking=true
entrance/motion: OFF
  → live остаётся, пока B активен
events: end, entrance/car-B
  → activeTracking=false; activeMotion=false
  → 500 ms grace → still; выключить playback
```

### 11.2. Разрыв во время объекта

```text
/ws disconnect; объект завершился во время разрыва
reconnect → onConnect
camera_activity: entrance.objects=[]
  → заменить старый список, удалить зависший объект
```

Исторический `end` не повторяется. Media восстанавливается отдельно, только если актуальный режим/состояние требует playback.

### 11.3. Continuous

```text
continuous или выбранная камера
motion=false; objects=[]; record=false
  → live разрешён при enabled=true и наличии stream
```

### 11.4. Возврат из background

```text
document hidden → media приостановлен согласно background policy
document visible → onConnect resync
snapshot + новые topics → актуальное состояние
media при необходимости → новая negotiation и новая готовность
```

Затемнение экрана само по себе не является событием Frigate API. Восстановление привязано к lifecycle/visibility и состоянию соединений клиента.

---

## Приложение A. Источники

Основной контракт выше не требует чтения исходников. Приложение фиксирует происхождение описанного поведения.

Репозитории: [Frigate](https://github.com/blakeblackshear/frigate), [go2rtc](https://github.com/AlexxIT/go2rtc). Пути относительны соответствующему репозиторию. Точный commit/tag исследованного checkout не установлен; документ не утверждает совпадение с deployed server.

| Часть контракта | Frigate source |
|---|---|
| Dashboard/grid, параметры режимов, выбор stream, alerts, fallback | `web/src/views/live/LiveDashboardView.tsx`, `web/src/views/live/DraggableGridLayout.tsx` |
| Режим выбранной камеры | `web/src/views/live/LiveCameraView.tsx` |
| Формулы активности, still intervals, liveReady и переходы | `web/src/components/player/LivePlayer.tsx` |
| Image URL, resize и refresh timing | `web/src/components/camera/CameraImage.tsx`, `web/src/components/camera/AutoUpdatingCameraImage.tsx` |
| Shared WS, envelope, resync, snapshot expansion | `web/src/api/WsProvider.tsx`, `web/src/api/ws.ts` |
| Object identity, stationary, labels, motion/offline | `web/src/hooks/use-camera-activity.ts` |
| Начальная и последующая статистика | `web/src/hooks/use-stats.ts` |
| Выбор транспорта, stream metadata | `web/src/hooks/use-camera-live-mode.ts`, `web/src/hooks/use-deferred-stream-metadata.ts` |
| Wire interaction MSE/WebRTC/JSMpeg | `web/src/components/player/MsePlayer.tsx`, `web/src/components/player/WebRTCPlayer.tsx`, `web/src/components/player/JSMpegPlayer.tsx` |
| Birdseye | `web/src/components/player/BirdseyeLivePlayer.tsx` |
| Формы данных | `web/src/types/ws.ts`, `web/src/types/frigateConfig.ts`, `web/src/types/review.ts` |
| WS routing/auth/permissions/delivery | `frigate/comms/ws.py`, `frigate/api/auth.py`, nginx configuration |
| Runtime snapshot, feature states | `frigate/comms/dispatcher.py` |
| Object/motion publishers | `frigate/track/object_processing.py`, `frigate/camera/state.py`, `frigate/track/tracked_object.py` |
| Still endpoint и query | `frigate/api/media.py`, `frigate/api/defs/query/media_query_parameters.py` |

| Часть media-контракта | go2rtc source |
|---|---|
| WS API, text/binary | `internal/api/ws/ws.go`, `internal/api/ws/README.md` |
| MSE handler, codecs | `internal/mp4/ws.go`, `pkg/mp4/helpers.go` |
| Init/fragments, keyframe gate | `pkg/mp4/consumer.go`, `pkg/mp4/muxer.go` |
| Подключение consumer | `internal/streams/streams.go`, `internal/streams/add_consumer.go` |

Локальные reference checkout: `frigate` и `go2rtc` в `<upstream-checkout>/`. Это временные материалы исследования, не зависимость приложения и не часть API.
