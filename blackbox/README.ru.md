**[English](README.md)** | Русский

# BlackBox-тесты Frigate Android

Внешние тесты на UiAutomator (модуль `:blackbox-tests`): установленный APK, настоящий экран, пользовательский путь. Они не знают внутренностей приложения и не заменяют JVM, Compose и device-проверки из [стратегии тестирования](../docs/testing-strategy.ru.md).

## Что внутри

| Путь | Назначение |
|---|---|
| `../blackbox-tests/` | тесты UiAutomator: вход, продление сессии, карусель → History, Live (камера, fullscreen, поворот, щипок, активность, снимки), видео и звук, Events (список, превью карточки, сессия, Motion) |
| `mock-server/make_media.py` | генератор тестового H.264 (нужен PyAV, ключ `BB_MEDIA_PYTHON`) |
| `mock-server/server.py` | управляемый mock Frigate (Python, только стандартная библиотека) |
| `lib/config.sh` | загрузка локального файла настроек |
| `run.sh` | раннер: mock, `adb reverse`, `adb install -r`, `am instrument`, уборка |
| `blackbox.local.env.example` | шаблон локальных настроек |

## Запуск

1. Скопируйте `blackbox.local.env.example` в `blackbox.local.env`, заполните и выполните `chmod 600`. Либо укажите другой файл через `BLACKBOX_CONFIG` (дальше проверяется `~/.config/frigate-blackbox.env`). Переменные окружения перекрывают файл.
2. Соберите APK: `./gradlew assembleBlackbox :blackbox-tests:assembleBlackbox` (Java 21).
3. `blackbox/run.sh` или `blackbox/run.sh LoginTest` (имя тестового класса).

**Сетевых значений по умолчанию нет.** Адрес, порт, serial устройства и URL сервера задаются только в локальном файле; незаданный ключ останавливает запуск. Файл не коммитится.

`blackbox/run.sh Класс` или `blackbox/run.sh Класс#метод` запускает один класс или один тест.

## Управление mock

Mock управляется через `/__control/*` и только по loopback: `review/<epoch>` (фиксированное начало обзора), `reviewed/on|off`, `timezone/<имя>`, `cameras/<n>`, `preview/<video|none>`, отказы `fail/<viewed|preview-media|thumb>/<код>/<раз>`, удержание ответов `hold/<preview-media|motion>/on|off`, `activity` и `ws/send` (push по WebSocket), `audio/<режим>`, `expire`, `reset`, журнал запросов `log` (без query, без `/__control/*`, до 500 записей). Тесты считают запросы по этому журналу.

Занятый декодер видео виден снаружи процесса приложения в `dumpsys media.resource_manager` (`Current Codec Usage: { VideoDec[ HW: n ] }`); строка есть, только пока декодер занят.

## Безопасность данных

- Сборка `blackbox` ставится как отдельное приложение с суффиксом `.blackbox`: профиль, сессия и credentials основного приложения не затрагиваются, `clearState` безопасен. Раннер отказывается работать с id без этого суффикса.
- Установка только через `adb install -r`.
- Тестовые логин и пароль тест читает у mock по loopback, а не из аргументов командной строки.
- Cleartext в debug разрешён только для loopback-имён; для mock адрес доходит до устройства через `adb reverse`. TLS не отключается.

## Границы

Видео mock отдаёт только при заданном `BB_MEDIA_PYTHON`; кадр проверяется по скриншотам. Реальные камеры, H.265, многочасовое воспроизведение и меню скоростей архива остаются на device acceptance по §4 стратегии. Фон приложения проверяется (HOME и возврат), screen off/on — нет.

## Правила для тестов

- Тест поворота обязан просить ориентацию, которой у activity ещё нет: запрос текущей не пересоздаёт activity, и тест проходит, ничего не доказывая. `ActivityInfo.SCREEN_ORIENTATION_*` и `Configuration.ORIENTATION_*` — разные константы, напрямую их не сравнивать. Поворот выполняет фреймворк (`UiDevice.setOrientationLeft/Natural`) и замораживает автоповорот, поэтому тест в `@After` вызывает `unfreezeRotation()`.
- Видео-тесты используют H.264 1280×720 из mock (`mock-server/make_media.py`); без `BB_MEDIA_PYTHON` они пропускаются. Ширина 1280 нужна и для зума: предел считается от ширины источника, на 320 px зума нет.
- Состояние Compose-accessibility (`stateDescription`) через UiAutomation не видно; такое состояние читают по цвету пикселей.
- Долгие тесты сами удерживают экран (`keepScreenOn()`): через ~30 с без касаний он гаснет и воспроизведение останавливается.
- `adb shell uiautomator dump` между двумя тапами поглощает следующий тап и даёт ложный диагноз «кнопка не реагирует». Для последовательных тапов использовать `screencap` без dump.
- Текст в поля вводится напрямую (`UiObject2.text`), без клавиатуры: клавиатура Samsung перекрывала форму.
