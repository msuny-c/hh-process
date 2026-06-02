#!/bin/sh
set -eu

# Generic WAR/WildFly controller for Helios/FreeBSD and Linux servers.
# It replaces the old java -jar controller: this project is deployed as ROOT.war.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# If this script is copied alone to APP_HOME, APP_DIR is SCRIPT_DIR.
# If used from source tree, APP_DIR is project root.
if [ -f "$SCRIPT_DIR/../pom.xml" ]; then
  APP_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
else
  APP_DIR=$SCRIPT_DIR
fi

ENV_FILE=${ENV_FILE:-"$APP_DIR/app.env"}
if [ ! -f "$ENV_FILE" ] && [ -f "$APP_DIR/infra/helios/app.env" ]; then
  ENV_FILE="$APP_DIR/infra/helios/app.env"
fi

load_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    . "$ENV_FILE"
    set +a
  fi

  : "${APP_HOME:=$APP_DIR}"
  : "${WILDFLY_HOME:=$HOME/wildfly/wildfly-40.0.0.Final}"
  : "${PORTBASE:=8080}"
  : "${WILDFLY_HTTP_PORT:=$PORTBASE}"
  : "${WILDFLY_AJP_PORT:=$((PORTBASE + 1))}"
  : "${WILDFLY_HTTPS_PORT:=$((PORTBASE + 2))}"
  : "${WILDFLY_MANAGEMENT_HTTP_PORT:=$((PORTBASE + 3))}"
  : "${WILDFLY_MANAGEMENT_HTTPS_PORT:=$((PORTBASE + 4))}"
  : "${WILDFLY_BIND_ADDRESS:=0.0.0.0}"
  : "${WILDFLY_MANAGEMENT_BIND_ADDRESS:=127.0.0.1}"
  : "${POSTGRES_HOST:=localhost}"
  : "${POSTGRES_PORT:=5432}"
  : "${POSTGRES_DB:=postgres}"
  : "${POSTGRES_USER:=postgres}"
  : "${POSTGRES_PASSWORD:=postgres}"
  : "${POSTGRES_SCHEMA:=public}"
  : "${NARAYANA_LOG_DIR:=$APP_HOME/transaction-logs}"
  : "${NARAYANA_NODE_IDENTIFIER:=hh-process-helios}"
  : "${NARAYANA_DEFAULT_TIMEOUT:=60}"
  : "${APP_SECURITY_USERS_XML:=$APP_HOME/data/users.xml}"
  : "${WS_ALLOWED_ORIGINS:=*}"
  : "${PID_FILE:=$APP_HOME/wildfly.pid}"
  : "${LOG_FILE:=$APP_HOME/wildfly.log}"
}

ensure_dirs() {
  mkdir -p "$APP_HOME" "$NARAYANA_LOG_DIR" "$(dirname -- "$APP_SECURITY_USERS_XML")"
}

war_source() {
  if [ -n "${WAR_SRC:-}" ] && [ -f "$WAR_SRC" ]; then
    echo "$WAR_SRC"
  elif [ -f "$APP_DIR/target/ROOT.war" ]; then
    echo "$APP_DIR/target/ROOT.war"
  elif [ -f "$APP_DIR/ROOT.war" ]; then
    echo "$APP_DIR/ROOT.war"
  elif [ -f "$APP_HOME/ROOT.war" ]; then
    echo "$APP_HOME/ROOT.war"
  else
    return 1
  fi
}

deploy_war() {
  load_env
  ensure_dirs

  if [ ! -d "$WILDFLY_HOME/standalone/deployments" ]; then
    echo "WildFly deployments directory not found: $WILDFLY_HOME/standalone/deployments" >&2
    echo "Set WILDFLY_HOME in $ENV_FILE" >&2
    exit 1
  fi

  WAR=$(war_source) || {
    echo "ROOT.war was not found. Run 'mvn clean package -DskipTests' or upload ROOT.war to $APP_HOME" >&2
    exit 1
  }

  echo "Deploying $WAR to $WILDFLY_HOME/standalone/deployments/ROOT.war"
  cp "$WAR" "$WILDFLY_HOME/standalone/deployments/ROOT.war"
  rm -f "$WILDFLY_HOME/standalone/deployments/ROOT.war.failed" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.deployed" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.isdeploying" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.undeployed"
  : > "$WILDFLY_HOME/standalone/deployments/ROOT.war.dodeploy"
}

get_pid() {
  load_env
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
      echo "$PID"
      return 0
    fi
    rm -f "$PID_FILE"
  fi
  return 1
}

cmd_status() {
  if PID=$(get_pid); then
    echo "Running: PID $PID"
  else
    echo "Stopped"
    return 1
  fi
}

cmd_start() {
  load_env
  ensure_dirs

  if PID=$(get_pid); then
    echo "Already running: PID $PID"
    return 0
  fi

  if [ ! -x "$WILDFLY_HOME/bin/standalone.sh" ]; then
    echo "WildFly not found or not executable: $WILDFLY_HOME/bin/standalone.sh" >&2
    exit 1
  fi

  deploy_war

  export POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_SCHEMA
  export NARAYANA_LOG_DIR NARAYANA_NODE_IDENTIFIER NARAYANA_DEFAULT_TIMEOUT APP_SECURITY_USERS_XML WS_ALLOWED_ORIGINS
  export SERVER_PORT="$WILDFLY_HTTP_PORT"

  if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  echo "Starting WildFly on HTTP port $WILDFLY_HTTP_PORT..."
  : > "$LOG_FILE"
  nohup "$WILDFLY_HOME/bin/standalone.sh" \
    -b "$WILDFLY_BIND_ADDRESS" \
    -bmanagement "$WILDFLY_MANAGEMENT_BIND_ADDRESS" \
    -Djboss.http.port="$WILDFLY_HTTP_PORT" \
    -Djboss.ajp.port="$WILDFLY_AJP_PORT" \
    -Djboss.https.port="$WILDFLY_HTTPS_PORT" \
    -Djboss.management.http.port="$WILDFLY_MANAGEMENT_HTTP_PORT" \
    -Djboss.management.https.port="$WILDFLY_MANAGEMENT_HTTPS_PORT" \
    > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "Started: PID $(cat "$PID_FILE")"
  echo "Log: $LOG_FILE"
}

cmd_stop() {
  load_env
  if PID=$(get_pid); then
    echo "Stopping WildFly PID $PID..."
    kill "$PID" 2>/dev/null || true
    i=0
    while [ "$i" -lt 40 ]; do
      if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "Stopped"
        return 0
      fi
      i=$((i + 1))
      sleep 1
    done
    echo "Force killing PID $PID"
    kill -9 "$PID" 2>/dev/null || true
    rm -f "$PID_FILE"
  else
    echo "Already stopped"
  fi
}

cmd_logs() {
  load_env
  if [ -f "$LOG_FILE" ]; then
    tail -f "$LOG_FILE"
  else
    echo "Log file not found: $LOG_FILE" >&2
    exit 1
  fi
}

cmd_restart() {
  cmd_stop
  sleep 2
  cmd_start
}

case "${1:-}" in
  deploy) deploy_war ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  logs) cmd_logs ;;
  *)
    echo "Usage: $0 {deploy|start|stop|restart|status|logs}" >&2
    exit 1
    ;;
esac
