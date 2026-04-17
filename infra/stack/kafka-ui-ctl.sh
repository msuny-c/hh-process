#!/usr/bin/env bash
# Kafka UI (kafbat) — standalone JAR from GitHub Releases, no Docker.
# Default bootstrap 127.0.0.1:9092 (broker on same host). Set KAFKA_UI_BOOTSTRAP to reach a remote broker (e.g. worker UI → stack:9092).
# Env:
#   KAFKA_UI_PORT — HTTP port (Spring SERVER_PORT)
#   KAFKA_UI_VERSION — release tag without v (default 1.4.2)
#   KAFKA_UI_BOOTSTRAP — Kafka bootstrap (default 127.0.0.1:9092)
#   KAFKA_UI_JAVA_OPTS — JVM args (default caps heap so the JVM won't try to grab ~25%+ RAM as one heap)
#   SKIP_KAFKA_UI — true/1 to skip
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
# Without explicit -Xmx, some JVMs on shared hosts pick an enormous heap and fail with:
# "Could not reserve enough space for XXXXk object heap"
KAFKA_UI_JAVA_OPTS="${KAFKA_UI_JAVA_OPTS:--Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m}"

ensure_jar() {
  mkdir -p "$UI_DIR"
  if [ -f "$JAR_PATH" ]; then
    echo "[kafka-ui] using existing ${JAR_PATH}"
    return 0
  fi
  echo "[kafka-ui] downloading ${JAR_NAME} ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$JAR_PATH" "$DOWNLOAD_URL"
  else
    wget -q -O "$JAR_PATH" "$DOWNLOAD_URL"
  fi
}

start() {
  if [ "${SKIP_KAFKA_UI:-}" = "true" ] || [ "${SKIP_KAFKA_UI:-}" = "1" ]; then
    echo "[kafka-ui] SKIP_KAFKA_UI is set — not starting."
    exit 0
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "[kafka-ui] java not found — install JDK 17+ (same as Kafka)." >&2
    exit 1
  fi

  # Clean up legacy Docker deployment if present.
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
