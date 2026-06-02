#!/usr/bin/env bash
set -euo pipefail

: "${JBOSS_HOME:=/opt/jboss/wildfly}"
: "${WILDFLY_BIND_ADDRESS:=0.0.0.0}"
: "${WILDFLY_MANAGEMENT_BIND_ADDRESS:=0.0.0.0}"
: "${WILDFLY_HTTP_PORT:=8080}"
: "${WILDFLY_HTTPS_PORT:=8443}"
: "${WILDFLY_AJP_PORT:=8009}"
: "${WILDFLY_MANAGEMENT_HTTP_PORT:=9990}"
: "${WILDFLY_MANAGEMENT_HTTPS_PORT:=9993}"

exec "${JBOSS_HOME}/bin/standalone.sh" \
  -b "${WILDFLY_BIND_ADDRESS}" \
  -bmanagement "${WILDFLY_MANAGEMENT_BIND_ADDRESS}" \
  -Djboss.http.port="${WILDFLY_HTTP_PORT}" \
  -Djboss.https.port="${WILDFLY_HTTPS_PORT}" \
  -Djboss.ajp.port="${WILDFLY_AJP_PORT}" \
  -Djboss.management.http.port="${WILDFLY_MANAGEMENT_HTTP_PORT}" \
  -Djboss.management.https.port="${WILDFLY_MANAGEMENT_HTTPS_PORT}"
