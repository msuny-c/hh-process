#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${ENV_FILE:-"$APP_DIR/infra/helios/app.env"}

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

: "${PORTBASE:=8080}"
: "${WILDFLY_HTTP_PORT:=$PORTBASE}"
: "${WILDFLY_AJP_PORT:=$((PORTBASE + 1))}"
: "${WILDFLY_HTTPS_PORT:=$((PORTBASE + 2))}"
: "${WILDFLY_MANAGEMENT_HTTP_PORT:=$((PORTBASE + 3))}"
: "${WILDFLY_MANAGEMENT_HTTPS_PORT:=$((PORTBASE + 4))}"
: "${WILDFLY_HOME:=$HOME/wildfly/wildfly-40.0.0.Final}"

printf 'OS: '
if command -v freebsd-version >/dev/null 2>&1; then
  freebsd-version
else
  uname -a
fi

printf 'Java: '
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | sed -n '1p'
else
  echo 'not found'
fi

printf 'WildFly home: %s\n' "$WILDFLY_HOME"
if [ -x "$WILDFLY_HOME/bin/standalone.sh" ]; then
  echo 'WildFly standalone.sh: OK'
else
  echo 'WildFly standalone.sh: NOT FOUND'
fi

printf 'WAR: '
if [ -f "$APP_DIR/target/ROOT.war" ]; then
  echo "$APP_DIR/target/ROOT.war"
elif [ -f "$APP_DIR/ROOT.war" ]; then
  echo "$APP_DIR/ROOT.war"
else
  echo 'not found'
fi

echo 'Configured ports:'
echo "  http             $WILDFLY_HTTP_PORT"
echo "  ajp              $WILDFLY_AJP_PORT"
echo "  https            $WILDFLY_HTTPS_PORT"
echo "  management-http  $WILDFLY_MANAGEMENT_HTTP_PORT"
echo "  management-https $WILDFLY_MANAGEMENT_HTTPS_PORT"

if command -v sockstat >/dev/null 2>&1; then
  echo 'Currently listening on these ports, if any:'
  for p in "$WILDFLY_HTTP_PORT" "$WILDFLY_AJP_PORT" "$WILDFLY_HTTPS_PORT" "$WILDFLY_MANAGEMENT_HTTP_PORT" "$WILDFLY_MANAGEMENT_HTTPS_PORT"; do
    sockstat -4 -6 -l 2>/dev/null | awk -v port=":$p" 'index($0, port) {print}' || true
  done
else
  echo 'sockstat not found; skipping port check'
fi
