#!/bin/sh
set -eu

# FreeBSD/Helios launcher for WAR deployment.
# This script intentionally avoids Docker and GNU-only shell features.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-"$APP_DIR/infra/helios/app.env"}

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

: "${APP_HOME:=$HOME/hh-process}"
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

if [ ! -x "$WILDFLY_HOME/bin/standalone.sh" ]; then
  echo "WildFly not found or not executable: $WILDFLY_HOME/bin/standalone.sh" >&2
  echo "Set WILDFLY_HOME in $ENV_FILE" >&2
  exit 1
fi

mkdir -p "$APP_HOME" "$NARAYANA_LOG_DIR" "$(dirname -- "$APP_SECURITY_USERS_XML")"

WAR_SRC=${WAR_SRC:-}
if [ -z "$WAR_SRC" ]; then
  if [ -f "$APP_DIR/target/ROOT.war" ]; then
    WAR_SRC="$APP_DIR/target/ROOT.war"
  elif [ -f "$APP_HOME/ROOT.war" ]; then
    WAR_SRC="$APP_HOME/ROOT.war"
  elif [ -f "$APP_DIR/ROOT.war" ]; then
    WAR_SRC="$APP_DIR/ROOT.war"
  fi
fi

if [ -n "$WAR_SRC" ] && [ -f "$WAR_SRC" ]; then
  mkdir -p "$WILDFLY_HOME/standalone/deployments"
  cp "$WAR_SRC" "$WILDFLY_HOME/standalone/deployments/ROOT.war"
  rm -f "$WILDFLY_HOME/standalone/deployments/ROOT.war.failed" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.deployed" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.isdeploying" \
        "$WILDFLY_HOME/standalone/deployments/ROOT.war.undeployed"
  : > "$WILDFLY_HOME/standalone/deployments/ROOT.war.dodeploy"
else
  echo "ROOT.war was not found. Build it first or set WAR_SRC=/path/to/ROOT.war" >&2
  exit 1
fi

export POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_SCHEMA
export NARAYANA_LOG_DIR NARAYANA_NODE_IDENTIFIER NARAYANA_DEFAULT_TIMEOUT APP_SECURITY_USERS_XML WS_ALLOWED_ORIGINS
export SERVER_PORT="$WILDFLY_HTTP_PORT"

if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

exec "$WILDFLY_HOME/bin/standalone.sh" \
  -b "$WILDFLY_BIND_ADDRESS" \
  -bmanagement "$WILDFLY_MANAGEMENT_BIND_ADDRESS" \
  -Djboss.http.port="$WILDFLY_HTTP_PORT" \
  -Djboss.ajp.port="$WILDFLY_AJP_PORT" \
  -Djboss.https.port="$WILDFLY_HTTPS_PORT" \
  -Djboss.management.http.port="$WILDFLY_MANAGEMENT_HTTP_PORT" \
  -Djboss.management.https.port="$WILDFLY_MANAGEMENT_HTTPS_PORT"
