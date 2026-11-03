#!/usr/bin/env bash
# Загрузка локального файла настроек BlackBox. Подключается через `source`.
# Файл читается построчно по белому списку ключей; eval не используется.

BB_KEYS="BB_PROFILE BB_DEVICE_SERIAL BB_APP_ID BB_APK BB_USERNAME BB_PASSWORD BB_MOCK_HOST BB_MOCK_PORT BB_SERVER_URL BB_MEDIA_PYTHON"

bb_fail() { echo "blackbox: $*" >&2; exit 2; }

bb_find_config() {
  local root="$1" candidate
  for candidate in "${BLACKBOX_CONFIG:-}" "$root/blackbox.local.env" "$HOME/.config/frigate-blackbox.env"; do
    [ -n "$candidate" ] && [ -f "$candidate" ] && { echo "$candidate"; return 0; }
  done
  return 1
}

bb_load_config() {
  local root="$1" file mode line key value
  file="$(bb_find_config "$root")" || bb_fail "файл настроек не найден. Скопируйте $root/blackbox.local.env.example в $root/blackbox.local.env (или задайте BLACKBOX_CONFIG) и заполните."
  mode="$(stat -c '%a' "$file")"
  case "$mode" in
    600|400) ;;
    *) bb_fail "права файла настроек $mode, нужны 600 (chmod 600 <файл>)" ;;
  esac
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    key="${line%%=*}"; value="${line#*=}"
    case " $BB_KEYS " in *" $key "*) ;; *) bb_fail "неизвестный ключ в файле настроек: $key" ;; esac
    # Переменная окружения сильнее файла.
    [ -z "${!key:-}" ] && export "$key=$value"
  done < "$file"
  BB_CONFIG_FILE="$file"
}

bb_require() {
  local key
  for key in "$@"; do
    local value="${!key:-}"
    case "$value" in ''|'<'*) bb_fail "не задан $key в файле настроек" ;; esac
  done
}

bb_validate() {
  bb_require BB_PROFILE BB_DEVICE_SERIAL BB_APP_ID BB_APK BB_USERNAME BB_PASSWORD
  case "$BB_PROFILE" in
    mock) bb_require BB_MOCK_HOST BB_MOCK_PORT ;;
    device) bb_require BB_SERVER_URL ;;
    *) bb_fail "BB_PROFILE должен быть mock или device" ;;
  esac
  case "$BB_APP_ID" in
    *.blackbox) ;;
    *) bb_fail "BB_APP_ID должен оканчиваться на .blackbox: основное приложение и его данные не трогаем" ;;
  esac
  [ -f "$BB_APK" ] || bb_fail "APK не найден: $BB_APK (соберите ./gradlew assembleBlackbox)"
}
