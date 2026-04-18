set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR"
JAR_NAME="${EIS_JAR_NAME:-eis.jar}"
PID_FILE="$APP_DIR/eis.pid"
LOG_FILE="$APP_DIR/eis.log"
ENV_FILE="$APP_DIR/eis.env"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx256m}"

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

cmd_stop() {
  if pid=$(get_pid); then
    echo "Stopping external-eis PID $pid..."
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      if ! kill -0 "$pid" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Stopped"
        return 0
      fi
      sleep 1
    done
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
  mkdir -p "$APP_DIR"
  : > "$LOG_FILE"
  echo "Starting external-eis $JAR_NAME..."
  nohup $JAVA_BIN $JAVA_OPTS -jar "$JAR_NAME" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "Started (PID $(cat "$PID_FILE"))"
}

cmd_restart() {
  cmd_stop
  sleep 1
  cmd_start
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  *)
    echo "Usage: $0 {start|stop|restart}" >&2
    exit 1
    ;;
esac
