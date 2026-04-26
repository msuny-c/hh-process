#!/usr/bin/env bash
# Apache Kafka в режиме с Zookeeper (no KRaft). Ожидает Zookeeper на 127.0.0.1:2181.
# Env: KAFKA_ADVERTISED_HOST (обязателен), KAFKA_VERSION, SCALA_VERSION
set -euo pipefail

KAFKA_VERSION="${KAFKA_VERSION:-3.7.0}"
SCALA_VERSION="${SCALA_VERSION:-2.13}"
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
KAFKA_HOME="${KAFKA_HOME:-$INSTALL_ROOT/kafka}"
BROKER_CONFIG="${KAFKA_BROKER_CONFIG:-$KAFKA_HOME/config/server-zk.properties}"
LOG_DIR="${KAFKA_LOG_DIRS:-$INSTALL_ROOT/kafka-logs-zk}"
ADVERTISED_HOST="${KAFKA_ADVERTISED_HOST:?export KAFKA_ADVERTISED_HOST}"
ZK_CONNECT="${KAFKA_ZOOKEEPER_CONNECT:-127.0.0.1:2181}"

if ! command -v bash >/dev/null 2>&1; then
  echo "bash is required" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java (JDK 17+) is required" >&2
  exit 1
fi

fix_kafka_bin_shebangs() {
  [ -d "${KAFKA_HOME}/bin" ] || return 0
  local bash_exe f line1 tmp
  bash_exe="$(command -v bash)"
  [ -n "$bash_exe" ] || return 0
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    line1="$(head -n1 "$f" | tr -d '\r')"
    [ "$line1" = "#!/bin/bash" ] || continue
    tmp="${f}.$$"
    { echo "#!${bash_exe}"; tail -n +2 "$f"; } >"$tmp" && mv "$tmp" "$f" && chmod a+x "$f"
  done < <(find "${KAFKA_HOME}/bin" -type f -name '*.sh' 2>/dev/null)
}

ARCHIVE="kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz"
DOWNLOAD_URL="https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/${ARCHIVE}"
EXTRACTED_DIR="$INSTALL_ROOT/kafka_${SCALA_VERSION}-${KAFKA_VERSION}"

mkdir -p "$INSTALL_ROOT"
cd "$INSTALL_ROOT"

if [ -f "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
  echo "Kafka ${KAFKA_VERSION} already at $KAFKA_HOME"
elif [ -f "$EXTRACTED_DIR/bin/kafka-server-start.sh" ]; then
  mv "$EXTRACTED_DIR" "$KAFKA_HOME"
elif [ -f "$INSTALL_ROOT/$ARCHIVE" ]; then
  tar -xzf "$INSTALL_ROOT/$ARCHIVE" -C "$INSTALL_ROOT"
  rm -f "$INSTALL_ROOT/$ARCHIVE"
  mv "$EXTRACTED_DIR" "$KAFKA_HOME"
else
  echo "Downloading $ARCHIVE ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$ARCHIVE" "$DOWNLOAD_URL"
  else
    wget -q -O "$ARCHIVE" "$DOWNLOAD_URL"
  fi
  tar -xzf "$ARCHIVE" -C "$INSTALL_ROOT"
  rm -f "$ARCHIVE"
  mv "$EXTRACTED_DIR" "$KAFKA_HOME"
fi

fix_kafka_bin_shebangs
mkdir -p "$LOG_DIR"

cat >"$BROKER_CONFIG" <<EOF
broker.id=0
zookeeper.connect=${ZK_CONNECT}
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://${ADVERTISED_HOST}:9092
num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600
log.dirs=${LOG_DIR}
num.partitions=2
default.replication.factor=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
min.insync.replicas=1
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=true
EOF

echo "Kafka (Zookeeper mode) config: $BROKER_CONFIG"
echo "Start Zookeeper first, then: KAFKA_BROKER_CONFIG=$BROKER_CONFIG $(cd "$(dirname "$0")" && pwd)/kafka-ctl.sh start"
