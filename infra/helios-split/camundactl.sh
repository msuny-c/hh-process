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

cleanup_demo() {
  echo "Cleaning Camunda default demo deployments and forms..."
  python3 - "$CAMUNDA_HTTP_PORT" <<'PY'
import json
import sys
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

port = sys.argv[1]
base = f"http://127.0.0.1:{port}/engine-rest"
demo_process_keys = ("invoice", "ReviewInvoice")
demo_filter_names = (
    "Accounting",
    "All Tasks",
    "All tasks",
    "John's Tasks",
    "Mary's Tasks",
    "My Group Tasks",
    "My Tasks",
    "Peter's Tasks",
)


def request(method, path):
    req = Request(f"{base}{path}", method=method)
    try:
        with urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except HTTPError as exc:
        if exc.code == 404:
            return None
        raise
    except URLError as exc:
        raise SystemExit(f"Camunda REST is unavailable for cleanup: {exc}") from exc


deleted_deployments = set()
for key in demo_process_keys:
    definitions = request("GET", f"/process-definition?key={quote(key)}") or []
    for definition in definitions:
        deployment_id = definition.get("deploymentId")
        if deployment_id and deployment_id not in deleted_deployments:
            request("DELETE", f"/deployment/{quote(deployment_id)}?cascade=true&skipCustomListeners=true&skipIoMappings=true")
            deleted_deployments.add(deployment_id)
            print(f"Deleted default Camunda demo deployment: processKey={key} deploymentId={deployment_id}")

for name in demo_filter_names:
    filters = request("GET", f"/filter?name={quote(name)}") or []
    for item in filters:
        filter_id = item.get("id")
        if filter_id:
            request("DELETE", f"/filter/{quote(filter_id)}")
            print(f"Deleted default Camunda demo Tasklist filter: name={name} filterId={filter_id}")

demo_owner_filters = request("GET", "/filter?owner=demo") or []
for item in demo_owner_filters:
    filter_id = item.get("id")
    if filter_id:
        request("DELETE", f"/filter/{quote(filter_id)}")
        print(f"Deleted default Camunda demo Tasklist filter: owner=demo filterId={filter_id}")

print("Camunda default demo cleanup finished.")
PY
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  cleanup-demo) cleanup_demo ;;
  log) tail -n 200 "$LOG_FILE" ;;
  *) echo "Usage: $0 {start|stop|restart|status|cleanup-demo|log}" >&2; exit 2 ;;
esac
