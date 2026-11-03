**[English](live-navigation-regressions.md)** | Русский

# Live: tap и статусы при переключении вкладок

2026-10-29. Исправление двух регрессов после редизайна.

## Причины и изменения

1. При замене старой CameraCard на LiveCameraTile исчез обработчик открытия камеры. В LiveCameraScreen возвращена выбранная камера, явный live demand независимо от motion, toolbar Back и system Back. Обычный dashboard сохраняет activity-driven policy; disabled/offline не обходятся. Snapshot cache и grid scroll state остаются у того же screen owner при detail/back.
2. CameraActivityWebSocket и activity map жили внутри условно монтируемого Live screen. Events удалял их вместе с media; возврат начинался с пустых статусов и зависел от нового snapshot. Подписка вынесена в CameraActivitySession на уровне ConnectedApp, над переключателем вкладок. Она продолжает получать updates в Events/Settings; media/polling по-прежнему освобождаются при уходе с Live. ON_STOP/выход из подключённой сессии останавливают activity socket.

## Regression tests

Оба дефекта покрыты тестами UiAutomator, которые прогоняют установленное приложение против mock Frigate (`blackbox-tests`):

- `LiveCameraTest.cameraOpensAndBackReturnsToTheGrid`: tap камеры открывает detail, Back возвращает сетку.
- `LiveFeedTest.activitySocketSurvivesTabSwitchesAndDeliversUpdatesWhileHidden`: обновление объектов, пришедшее при открытых Events, видно после возврата на Live, существующий person остаётся, число activity-соединений равно 1.

Для локальных тестовых серверов служит debug-only cleartext allowlist localhost/127.0.0.1. TLS verification не отключалась, release manifest не изменён.

## Свежая проверка

- `testDebugUnitTest assembleDebug`, Java 21: BUILD SUCCESSFUL.
- Установка только поверх приложения: installDebug/installDebugAndroidTest. Connected cleanup, uninstall, clear-data и очистка logcat не использовались.
- Реальное приложение: Awake, MainActivity focus, свежий PID 18031. Сохранённая сессия загрузилась без login/setup.
- Tap первой камеры: открыт detail, screenshot с decoded video визуально проверен; fresh Media3 firstFrame и READY в 11:47:08.249, после BUFFERING в 11:47:07.115.
- Back вернул сетку; прежний player release зарегистрирован в 11:47:58.025.
- Events действительно открыт (screenshot проверен), media release зарегистрирован; возврат в Live показал snapshots и motion-dot на street. Доставка object update во время Events детерминированно проверена transport/UI regression test, а не выведена из одного screenshot.

Это не закрывает отдельную проблему DIM и не является длительным sustained-playback acceptance всех камер. Во время detail→grid в текущем PID также была BUFFERING transition; отсутствие rebuffer не заявляется.
