#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/apps/hh-process}"
WF_HOME="${WF_HOME:-$HOME/apps/wildfly/wildfly-40.0.0.Final}"
ENV_FILE="${ENV_FILE:-$APP_DIR/app.env}"
LOG_FILE="${LOG_FILE:-$APP_DIR/wildfly.log}"
PID_FILE="${PID_FILE:-$APP_DIR/wildfly.pid}"

if [ -f "$ENV_FILE" ]; then
  set -a

  . "$ENV_FILE"
  set +a
fi

APP_HTTP_PORT="${APP_HTTP_PORT:-${SERVER_PORT:-18081}}"
APP_MGMT_PORT="${APP_MGMT_PORT:-19081}"
APP_HTTPS_PORT="${APP_HTTPS_PORT:-19443}"
APP_AJP_PORT="${APP_AJP_PORT:-19009}"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx768m -Dorg.springframework.boot.logging.LoggingSystem=none}"

mkdir -p "$APP_DIR" "$APP_DIR/data" "$APP_DIR/transaction-logs"

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

start() {
  if is_running; then
    echo "WildFly already running: pid $(cat "$PID_FILE")"
    return 0
  fi
  if [ ! -x "$WF_HOME/bin/standalone.sh" ]; then
    echo "WildFly not found: $WF_HOME" >&2
    exit 1
  fi
  echo "Starting WildFly: http=$APP_HTTP_PORT mgmt=$APP_MGMT_PORT"
  nohup "$WF_HOME/bin/standalone.sh" \
    -b 0.0.0.0 \
    -bmanagement 127.0.0.1 \
    -Djboss.http.port="$APP_HTTP_PORT" \
    -Djboss.management.http.port="$APP_MGMT_PORT" \
    -Djboss.https.port="$APP_HTTPS_PORT" \
    -Djboss.ajp.port="$APP_AJP_PORT" \
    > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "WildFly pid: $(cat "$PID_FILE")"
}

stop() {
  if is_running; then
    echo "Stopping WildFly pid $(cat "$PID_FILE")"
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    for _ in $(seq 1 30); do
      is_running || break
      sleep 1
    done
    if is_running; then
      echo "Killing WildFly pid $(cat "$PID_FILE")"
      kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
    fi
  fi
  rm -f "$PID_FILE"
}

status() {
  if is_running; then
    echo "running pid=$(cat "$PID_FILE") http=$APP_HTTP_PORT"
  else
    echo "stopped"
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  log) tail -n 200 "$LOG_FILE" ;;
  *) echo "Usage: $0 {start|stop|restart|status|log}" >&2; exit 2 ;;
esac
