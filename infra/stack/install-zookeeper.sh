#!/usr/bin/env bash
# Apache Zookeeper (для Kafka в режиме с Zookeeper на helios).
# Env: (optional) ZOOKEEPER_VERSION
set -euo pipefail

ZOOKEEPER_VERSION="${ZOOKEEPER_VERSION:-3.9.2}"
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
ZK_HOME="${ZOOKEEPER_HOME:-$INSTALL_ROOT/zookeeper}"
DATA_DIR="${ZOO_DATA_DIR:-$INSTALL_ROOT/zk-data}"
CONFIG_FILE="${ZOO_CONFIG_FILE:-$ZK_HOME/conf/zoo.cfg}"

if ! command -v java >/dev/null 2>&1; then
  echo "Java is required (JDK 17+)." >&2
  exit 1
fi

ARCHIVE="apache-zookeeper-${ZOOKEEPER_VERSION}-bin.tar.gz"
DOWNLOAD_URL="https://archive.apache.org/dist/zookeeper/zookeeper-${ZOOKEEPER_VERSION}/${ARCHIVE}"
EXTRACTED="apache-zookeeper-${ZOOKEEPER_VERSION}-bin"

mkdir -p "$INSTALL_ROOT"
cd "$INSTALL_ROOT"

if [ -f "$ZK_HOME/bin/zkServer.sh" ]; then
  echo "Zookeeper already at $ZK_HOME"
else
  if [ -d "$EXTRACTED" ] && [ -f "$EXTRACTED/bin/zkServer.sh" ]; then
    mv "$EXTRACTED" "$ZK_HOME"
  else
    echo "Downloading $ARCHIVE ..."
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL -o "$ARCHIVE" "$DOWNLOAD_URL"
    else
      wget -q -O "$ARCHIVE" "$DOWNLOAD_URL"
    fi
    tar -xzf "$ARCHIVE" -C "$INSTALL_ROOT"
    rm -f "$ARCHIVE"
    mv "$INSTALL_ROOT/$EXTRACTED" "$ZK_HOME"
  fi
fi

mkdir -p "$DATA_DIR"
cat >"$CONFIG_FILE" <<EOF
tickTime=2000
dataDir=${DATA_DIR}
clientPort=2181
maxClientCnxns=128
admin.enableServer=false
EOF

echo "Zookeeper ready. Config: $CONFIG_FILE"
echo "Control: $(cd "$(dirname "$0")" && pwd)/zookeeper-ctl.sh {start|stop|restart|status}"
