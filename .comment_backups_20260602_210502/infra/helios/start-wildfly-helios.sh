#!/bin/sh
set -eu

# Backward-compatible name. Prefer start-wildfly-freebsd.sh.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/start-wildfly-freebsd.sh" "$@"
