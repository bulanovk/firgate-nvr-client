#!/usr/bin/env bash
# Запуск BlackBox: настройки -> (mock) -> adb reverse -> install -r -> UiAutomator -> уборка.
# Использование: blackbox/run.sh [имя тестового класса]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/config.sh
source "$ROOT/lib/config.sh"

bb_load_config "$ROOT"
bb_validate

command -v adb >/dev/null || bb_fail "adb не найден"
adb -s "$BB_DEVICE_SERIAL" get-state >/dev/null 2>&1 || bb_fail "устройство из BB_DEVICE_SERIAL недоступно"

OUT="${CLAUDE_JOB_DIR:-${TMPDIR:-/tmp}}/tmp/blackbox"
mkdir -p "$OUT"
MOCK_PID=""
MEDIA_ARGS=()

cleanup() {
  if [ -n "$MOCK_PID" ]; then
    kill "$MOCK_PID" 2>/dev/null || true
    # Ждём выхода (порт должен освободиться до следующего запуска); при зависании — SIGKILL.
    for _ in $(seq 1 30); do kill -0 "$MOCK_PID" 2>/dev/null || break; sleep 0.1; done
    kill -9 "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
  fi
  if [ "$BB_PROFILE" = mock ]; then
    adb -s "$BB_DEVICE_SERIAL" reverse --remove "tcp:$BB_MOCK_PORT" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ "$BB_PROFILE" = mock ]; then
  command -v python3 >/dev/null || bb_fail "python3 не найден"
  # Видео для mock (необязательно): Python с PyAV и numpy (venv) генерирует H.264-фрагменты.
  if [ -n "${BB_MEDIA_PYTHON:-}" ]; then
    "$BB_MEDIA_PYTHON" "$ROOT/mock-server/make_media.py" "$OUT/media" >/dev/null || bb_fail "не удалось сгенерировать видео (BB_MEDIA_PYTHON)"
    export BB_MEDIA_DIR="$OUT/media"; MEDIA_ARGS=(-e bbMedia 1)
    "$BB_MEDIA_PYTHON" "$ROOT/mock-server/make_media.py" "$OUT/media-audio" audio >/dev/null || bb_fail "не удалось сгенерировать видео со звуком"
    export BB_MEDIA_AUDIO_DIR="$OUT/media-audio"
  fi
  python3 -I "$ROOT/mock-server/server.py" >"$OUT/mock.log" 2>&1 &
  MOCK_PID=$!
  for _ in $(seq 1 50); do
    grep -q 'готов' "$OUT/mock.log" 2>/dev/null && break
    kill -0 "$MOCK_PID" 2>/dev/null || { cat "$OUT/mock.log" >&2; bb_fail "mock не стартовал"; }
    sleep 0.1
  done
  adb -s "$BB_DEVICE_SERIAL" reverse "tcp:$BB_MOCK_PORT" "tcp:$BB_MOCK_PORT" >/dev/null
fi

# Экран должен быть включён и разблокирован до старта: на погашенном экране скриншот чёрный.
# Разовое пробуждение; настройки устройства (тайм-аут, stay-on) не меняются.
adb -s "$BB_DEVICE_SERIAL" shell input keyevent KEYCODE_WAKEUP
sleep 1
adb -s "$BB_DEVICE_SERIAL" shell dumpsys power | grep -q 'mWakefulness=Awake' \
  || bb_fail "экран не включился (mWakefulness != Awake)"
adb -s "$BB_DEVICE_SERIAL" shell dumpsys window | grep -q 'isKeyguardShowing=true' \
  && bb_fail "на устройстве включена блокировка экрана: разблокируйте его вручную"

# install -r сохраняет данные blackbox-приложения; основное приложение (другой id) не затрагивается.
adb -s "$BB_DEVICE_SERIAL" install -r "$BB_APK" >/dev/null

TARGET="${1:-}"
# serial устройства тоже сетевая деталь: вычищаем его из артефактов при выходе.
scrub() {
  grep -rlF "$BB_DEVICE_SERIAL" "$OUT" 2>/dev/null | while IFS= read -r f; do
    sed -i "s|$BB_DEVICE_SERIAL|<serial>|g" "$f"
  done
}
# scrub не должен обрывать уборку: при set -e и pipefail пустой grep иначе пропускал cleanup.
trap 'scrub || true; cleanup' EXIT
# Сигналы (SIGPIPE от `| head`, Ctrl-C, kill) тоже должны вести к уборке: иначе mock остаётся на порту.
trap 'exit 1' PIPE INT TERM HUP
# UiAutomator: тестовый APK (модуль :blackbox-tests) управляет blackbox-приложением снаружи.
TEST_APK="$ROOT/../blackbox-tests/build/outputs/apk/blackbox/blackbox-tests-blackbox.apk"
[ -f "$TEST_APK" ] || bb_fail "нет тестового APK: ./gradlew :blackbox-tests:assembleBlackbox"
adb -s "$BB_DEVICE_SERIAL" install -r "$TEST_APK" >/dev/null
CLASS_ARGS=()
[ -n "$TARGET" ] && CLASS_ARGS=(-e class "com.frigate.android.blackbox.$TARGET")
adb -s "$BB_DEVICE_SERIAL" shell am instrument -w -r \
  "${MEDIA_ARGS[@]}" -e bbAppId "$BB_APP_ID" -e bbMockHost "$BB_MOCK_HOST" -e bbMockPort "$BB_MOCK_PORT" \
  "${CLASS_ARGS[@]}" \
  com.frigate.android.blackbox.tests/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"
grep -q '^OK (' "$OUT/instrument.txt" || { echo "blackbox: тесты не прошли" >&2; exit 1; }
echo "blackbox: отчёт в $OUT"
