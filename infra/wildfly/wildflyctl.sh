#!/usr/bin/env bash
set -euo pipefail

CAMUNDA_HOME="${CAMUNDA_HOME:-$HOME/camunda/camunda-bpm-wildfly-7}"
WF_HOME="${WF_HOME:-$CAMUNDA_HOME/server/wildfly-18.0.0.Final}"
APP_DIR="${APP_DIR:-$HOME/apps/blps}"
ENV_FILE="${ENV_FILE:-$APP_DIR/app.env}"
PID_FILE="${PID_FILE:-$APP_DIR/wildfly.pid}"
LOG_FILE="${LOG_FILE:-$APP_DIR/wildfly.log}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx768m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED}"

load_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    . "$ENV_FILE"
    set +a
  fi
}

get_pid() {
  if [ -f "$PID_FILE" ]; then
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "$pid"
      return 0
    fi
    rm -f "$PID_FILE"
  fi
  return 1
}

status() {
  if pid="$(get_pid)"; then
    echo "Running (PID $pid)"
    return 0
  fi
  echo "Stopped"
  return 1
}

stop() {
  if pid="$(get_pid)"; then
    echo "Stopping WildFly PID $pid"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 45); do
      if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Stopped"
        return 0
      fi
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "Killed"
    return 0
  fi
  echo "Already stopped"
}

start() {
  if get_pid >/dev/null; then
    status
    return 0
  fi
  test -x "$WF_HOME/bin/standalone.sh"
  mkdir -p "$APP_DIR" "$APP_DIR/data" "$APP_DIR/transaction-logs"
  load_env
  export JAVA_OPTS
  export JBOSS_HOME="$WF_HOME"
  export NARAYANA_LOG_DIR="${NARAYANA_LOG_DIR:-$APP_DIR/transaction-logs}"
  export APP_SECURITY_USERS_XML="${APP_SECURITY_USERS_XML:-$APP_DIR/data/users.xml}"
  : > "$LOG_FILE"
  nohup "$WF_HOME/bin/standalone.sh" -b 0.0.0.0 > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "Started (PID $(cat "$PID_FILE"))"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 2; start ;;
  status) status ;;
  *) echo "Usage: $0 {start|stop|restart|status}" >&2; exit 1 ;;
esac
