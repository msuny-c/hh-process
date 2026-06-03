#!/usr/bin/env bash
set -euo pipefail

CAMUNDA_DIR="${CAMUNDA_DIR:-$HOME/apps/camunda}"
CAMUNDA_HOME="${CAMUNDA_HOME:-$CAMUNDA_DIR/camunda-bpm-tomcat-7.21.0}"
CAMUNDA_HTTP_PORT="${CAMUNDA_HTTP_PORT:-18082}"
CAMUNDA_SHUTDOWN_PORT="${CAMUNDA_SHUTDOWN_PORT:-19082}"
CAMUNDA_AJP_PORT="${CAMUNDA_AJP_PORT:-19083}"
LOG_FILE="${LOG_FILE:-$CAMUNDA_DIR/camunda.log}"
PID_FILE="${PID_FILE:-$CAMUNDA_DIR/camunda.pid}"

mkdir -p "$CAMUNDA_DIR"

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

patch_ports() {
  local server_xml="$CAMUNDA_HOME/server/apache-tomcat-*/conf/server.xml"
  # Expand glob safely.
  for f in $server_xml; do
    [ -f "$f" ] || continue
    python3 - "$f" "$CAMUNDA_HTTP_PORT" "$CAMUNDA_SHUTDOWN_PORT" "$CAMUNDA_AJP_PORT" <<'PY'
from pathlib import Path
import re, sys
path = Path(sys.argv[1])
http, shutdown, ajp = sys.argv[2:5]
s = path.read_text()
s = re.sub(r'port="8080" protocol="HTTP/1\.1"', f'port="{http}" protocol="HTTP/1.1"', s)
s = re.sub(r'<Server port="8005"', f'<Server port="{shutdown}"', s)
s = re.sub(r'port="8009" protocol="AJP/1\.3"', f'port="{ajp}" protocol="AJP/1.3"', s)
path.write_text(s)
PY
  done
}

start() {
  if is_running; then
    echo "Camunda already running: pid $(cat "$PID_FILE")"
    return 0
  fi
  if [ ! -x "$CAMUNDA_HOME/start-camunda.sh" ]; then
    echo "Camunda Tomcat distribution not found: $CAMUNDA_HOME" >&2
    exit 1
  fi
  patch_ports
  echo "Starting Camunda standalone: http=$CAMUNDA_HTTP_PORT"
  cd "$CAMUNDA_HOME"
  nohup ./start-camunda.sh > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "Camunda pid: $(cat "$PID_FILE")"
}

stop() {
  if [ -x "$CAMUNDA_HOME/shutdown-camunda.sh" ]; then
    (cd "$CAMUNDA_HOME" && ./shutdown-camunda.sh) >/dev/null 2>&1 || true
  fi
  if is_running; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    for _ in $(seq 1 30); do
      is_running || break
      sleep 1
    done
    is_running && kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
}

status() {
  if is_running; then
    echo "running pid=$(cat "$PID_FILE") http=$CAMUNDA_HTTP_PORT"
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
