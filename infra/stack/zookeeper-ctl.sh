#!/usr/bin/env bash
# Управление Zookeeper, установленным install-zookeeper.sh
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
export ZK_HOME="${ZOOKEEPER_HOME:-$INSTALL_ROOT/zookeeper}"
export ZOOCFGDIR="${ZOOCFGDIR:-$ZK_HOME/conf}"
LOG_FILE="${ZOO_LOG_FILE:-$INSTALL_ROOT/zookeeper.log}"

cd "$ZK_HOME"

case "${1:-}" in
  start)
    if [ ! -f "$ZK_HOME/bin/zkServer.sh" ]; then
      echo "Zookeeper not installed. Run install-zookeeper.sh first." >&2
      exit 1
    fi
    bash bin/zkServer.sh start 2>&1 | tee -a "$LOG_FILE"
    ;;
  stop)
    bash bin/zkServer.sh stop 2>&1 | tee -a "$LOG_FILE" || true
    ;;
  restart)
    bash bin/zkServer.sh stop 2>/dev/null || true
    sleep 2
    bash bin/zkServer.sh start 2>&1 | tee -a "$LOG_FILE"
    ;;
  status)
    bash bin/zkServer.sh status || true
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}" >&2
    exit 1
    ;;
esac
