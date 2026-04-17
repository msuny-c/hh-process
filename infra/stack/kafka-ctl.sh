#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
KAFKA_HOME="${KAFKA_HOME:-$INSTALL_ROOT/kafka}"
CONFIG_PATH="${CONFIG_PATH:-$KAFKA_HOME/config/kraft/server.properties}"
LOG_FILE="${KAFKA_LOG_FILE:-$INSTALL_ROOT/kafka.log}"
PID_FILE="${KAFKA_PID_FILE:-$INSTALL_ROOT/kafka.pid}"

start() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Kafka already running (PID $(cat "$PID_FILE"))"
    return 0
  fi
  if ! [ -f "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
    echo "Kafka not installed. Run install-kafka-kraft.sh first." >&2
    exit 1
  fi
  if ! command -v bash >/dev/null 2>&1; then
    echo "bash is required to run Kafka scripts." >&2
    exit 1
  fi
  # kafka-run-class.sh picks Java 8 GC flags (-Xloggc, PrintGCDateStamps) when it mis-detects the JDK
  # or when KAFKA_GC_LOG_OPTS is empty. JDK 9+ rejects those; use unified GC logging instead.
  if [ -z "${KAFKA_GC_LOG_OPTS:-}" ]; then
    GC_LOG="${INSTALL_ROOT}/kafka-gc.log"
    export KAFKA_GC_LOG_OPTS="-Xlog:gc*:file=${GC_LOG}:time,tags:filecount=10,filesize=100M"
  fi
  : >"$LOG_FILE"
  nohup bash "$KAFKA_HOME/bin/kafka-server-start.sh" "$CONFIG_PATH" >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  echo "Kafka starting (PID $(cat "$PID_FILE")), log: $LOG_FILE"
}

stop() {
  if [ ! -f "$PID_FILE" ]; then
    echo "No pid file; trying kafka-server-stop"
    bash "$KAFKA_HOME/bin/kafka-server-stop.sh" 2>/dev/null || true
    return 0
  fi
  pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping Kafka PID $pid..."
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
      if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Stopped"
        return 0
      fi
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  bash "$KAFKA_HOME/bin/kafka-server-stop.sh" 2>/dev/null || true
  echo "Stopped"
}

status() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Running (PID $(cat "$PID_FILE"))"
    return 0
  fi
  echo "Stopped"
  return 1
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 2; start ;;
  status) status ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}" >&2
    exit 1
    ;;
esac
