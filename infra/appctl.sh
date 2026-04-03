set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR"
JAR_NAME="app.jar"
PID_FILE="$APP_DIR/app.pid"
LOG_FILE="$APP_DIR/app.log"
ENV_FILE="$APP_DIR/app.env"
JAVA_BIN="${JAVA_BIN:-java}"
NARAYANA_LOG_DIR="${NARAYANA_LOG_DIR:-$APP_DIR/transaction-logs}"
APP_SECURITY_USERS_XML="${APP_SECURITY_USERS_XML:-$APP_DIR/data/users.xml}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED}"

cd "$APP_DIR"

if [ ! -f "$JAR_NAME" ]; then
  echo "Error: $JAR_NAME not found in $APP_DIR" >&2
  exit 1
fi

load_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    . "$ENV_FILE"
    set +a
  fi
  NARAYANA_LOG_DIR="${NARAYANA_LOG_DIR:-$APP_DIR/transaction-logs}"
  APP_SECURITY_USERS_XML="${APP_SECURITY_USERS_XML:-$APP_DIR/data/users.xml}"
}

wait_for_db() {
  local host port
  host="${POSTGRES_HOST:-}"
  port="${POSTGRES_PORT:-}"

  if [ -z "$host" ] || [ -z "$port" ]; then
    return 0
  fi

  echo "Waiting for DB at ${host}:${port}..."
  for _ in $(seq 1 60); do
    if nc -z "$host" "$port" 2>/dev/null; then
      echo "DB is reachable"
      return 0
    fi
    sleep 1
  done

  echo "Warning: DB ${host}:${port} is still unreachable, starting app anyway" >&2
  return 0
}

ensure_runtime_dirs() {
  mkdir -p "$NARAYANA_LOG_DIR" "$(dirname "$APP_SECURITY_USERS_XML")"
}

get_pid() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
      echo "$pid"
      return 0
    fi
    rm -f "$PID_FILE"
  fi
  return 1
}

cmd_status() {
  if pid=$(get_pid); then
    echo "Running (PID $pid)"
    return 0
  else
    echo "Stopped"
    return 1
  fi
}

cmd_stop() {
  if pid=$(get_pid); then
    echo "Stopping PID $pid..."
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
      if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Stopped"
        return 0
      fi
      sleep 1
    done
    echo "Force killing PID $pid"
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
  else
    echo "Already stopped"
  fi
}

cmd_start() {
  if get_pid >/dev/null; then
    echo "Already running (PID $(get_pid))"
    return 0
  fi

  load_env
  ensure_runtime_dirs
  wait_for_db
  echo "Starting $JAR_NAME..."
  : > "$LOG_FILE"
  nohup $JAVA_BIN $JAVA_OPTS -Dnarayana.log-dir="$NARAYANA_LOG_DIR" -Dapp.security.users-xml-path="$APP_SECURITY_USERS_XML" -jar "$JAR_NAME" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "Started (PID $(cat "$PID_FILE"))"
}

cmd_restart() {
  cmd_stop
  sleep 2
  cmd_start
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}" >&2
    exit 1
    ;;
esac
