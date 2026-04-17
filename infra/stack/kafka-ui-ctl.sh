#!/usr/bin/env bash
# Kafka UI (kafbat) — standalone JAR from GitHub Releases, no Docker.
# Актуальная версия по умолчанию: см. KAFKA_UI_VERSION (GitHub: kafbat/kafka-ui releases).
# Env:
#   KAFKA_UI_PORT — HTTP (Spring SERVER_PORT)
#   KAFKA_UI_VERSION — без v (по умолчанию 1.4.2 = последний стабильный релиз)
#   KAFKA_UI_BOOTSTRAP — Kafka bootstrap (на worker до брокера на stack: host:9092)
#   KAFKA_UI_JAVA_OPTS — JVM (по умолчанию ограничение heap)
#   SKIP_KAFKA_UI — true/1 пропуск
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
UI_DIR="${INSTALL_ROOT}/kafka-ui"
KAFKA_UI_PORT="${KAFKA_UI_PORT:-8081}"
KAFKA_UI_VERSION="${KAFKA_UI_VERSION:-1.4.2}"
JAR_NAME="api-v${KAFKA_UI_VERSION}.jar"
JAR_PATH="${UI_DIR}/${JAR_NAME}"
DOWNLOAD_URL="https://github.com/kafbat/kafka-ui/releases/download/v${KAFKA_UI_VERSION}/${JAR_NAME}"
PID_FILE="${INSTALL_ROOT}/kafka-ui.pid"
LOG_FILE="${INSTALL_ROOT}/kafka-ui.log"
BOOTSTRAP="${KAFKA_UI_BOOTSTRAP:-127.0.0.1:9092}"
KAFKA_UI_JAVA_OPTS="${KAFKA_UI_JAVA_OPTS:--Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m}"

download_to_tmp() {
  local url="$1" dest="$2"
  local tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/kafkaui.XXXXXX" 2>/dev/null)" || tmp="${dest}.part"
  rm -f "$tmp"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --connect-timeout 30 --retry 2 --retry-delay 2 -o "$tmp" "$url"
  else
    fetch -o "$tmp" "$url" 2>/dev/null || wget -q -T 30 -O "$tmp" "$url"
  fi
  mv -f "$tmp" "$dest"
}

ensure_jar() {
  mkdir -p "$UI_DIR"
  if [ -f "$JAR_PATH" ] && [ -s "$JAR_PATH" ]; then
    echo "[kafka-ui] using existing ${JAR_PATH}"
    return 0
  fi
  rm -f "$JAR_PATH"
  if ! touch "$UI_DIR/.write_test" 2>/dev/null; then
    echo "[kafka-ui] cannot write to ${UI_DIR}" >&2
    df -h "$UI_DIR" 2>/dev/null || true
    exit 1
  fi
  rm -f "$UI_DIR/.write_test"

  local tmp
  tmp=""
  if command -v mktemp >/dev/null 2>&1; then
    tmp="$(mktemp "${TMPDIR:-/tmp}/kafkaui.XXXXXX" 2>/dev/null)" || tmp=""
  fi
  if [ -z "$tmp" ] || [ ! -w "$(dirname "$tmp")" ]; then
    tmp="${UI_DIR}/.${JAR_NAME}.download"
  fi

  echo "[kafka-ui] downloading ${JAR_NAME} ..."
  local attempt
  for attempt in 1 2 3; do
    rm -f "$tmp"
    if command -v curl >/dev/null 2>&1; then
      if curl -fsSL --connect-timeout 30 --retry 2 --retry-delay 2 -o "$tmp" "$DOWNLOAD_URL"; then
        break
      fi
    else
      if fetch -o "$tmp" "$DOWNLOAD_URL" 2>/dev/null || wget -q -T 30 -O "$tmp" "$DOWNLOAD_URL"; then
        break
      fi
    fi
    echo "[kafka-ui] download attempt ${attempt}/3 failed" >&2
    if [ "$attempt" -eq 3 ]; then
      df -h "${TMPDIR:-/tmp}" "$UI_DIR" 2>/dev/null || true
      rm -f "$tmp"
      exit 1
    fi
    sleep 4
  done

  if [ ! -s "$tmp" ]; then
    echo "[kafka-ui] downloaded file is empty" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv -f "$tmp" "$JAR_PATH"
  echo "[kafka-ui] saved ${JAR_PATH} ($(wc -c < "$JAR_PATH" | tr -d ' ') bytes)"
}

start() {
  if [ "${SKIP_KAFKA_UI:-}" = "true" ] || [ "${SKIP_KAFKA_UI:-}" = "1" ]; then
    echo "[kafka-ui] SKIP_KAFKA_UI — not starting."
    exit 0
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "[kafka-ui] java not found — install JDK 17+" >&2
    exit 1
  fi

  if command -v docker >/dev/null 2>&1; then
    docker rm -f hh-process-kafka-ui 2>/dev/null || true
  fi

  ensure_jar

  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "[kafka-ui] already running (PID $(cat "$PID_FILE"))"
    return 0
  fi

  export SERVER_PORT="${KAFKA_UI_PORT}"
  export DYNAMIC_CONFIG_ENABLED=true
  export KAFKA_CLUSTERS_0_NAME=local
  export KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS="${BOOTSTRAP}"

  : >"$LOG_FILE"
  echo "[kafka-ui] JVM: ${KAFKA_UI_JAVA_OPTS}"
  # shellcheck disable=SC2086
  nohup java ${KAFKA_UI_JAVA_OPTS} \
    --add-opens java.rmi/javax.rmi.ssl=ALL-UNNAMED \
    -jar "$JAR_PATH" >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  echo "[kafka-ui] started PID $(cat "$PID_FILE"), log ${LOG_FILE}"
  echo "[kafka-ui] http://0.0.0.0:${KAFKA_UI_PORT}  (bootstrap ${BOOTSTRAP})"
}

stop() {
  if [ -f "$PID_FILE" ]; then
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 15); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$PID_FILE"
  if command -v docker >/dev/null 2>&1; then
    docker rm -f hh-process-kafka-ui 2>/dev/null || true
  fi
  echo "[kafka-ui] stopped"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  *)
    echo "Usage: $0 {start|stop|restart}" >&2
    exit 1
    ;;
esac
