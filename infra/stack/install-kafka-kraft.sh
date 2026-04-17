#!/usr/bin/env bash
# Idempotent install of Apache Kafka (KRaft, no Zookeeper, no Docker).
# Requires: curl or wget, Java 17+.
# Env:
#   KAFKA_ADVERTISED_HOST — hostname/IP clients use (same as STACK_HOST / SSH_HOST for workers).
set -euo pipefail

KAFKA_VERSION="${KAFKA_VERSION:-3.7.0}"
SCALA_VERSION="${SCALA_VERSION:-2.13}"
INSTALL_ROOT="${INSTALL_ROOT:-$HOME/hh-process}"
KAFKA_HOME="${KAFKA_HOME:-$INSTALL_ROOT/kafka}"
DATA_DIR="${DATA_DIR:-$INSTALL_ROOT/kafka-data}"
CONFIG_PATH="${CONFIG_PATH:-$KAFKA_HOME/config/kraft/server.properties}"
ADVERTISED_HOST="${KAFKA_ADVERTISED_HOST:?export KAFKA_ADVERTISED_HOST}"

if ! command -v bash >/dev/null 2>&1; then
  echo "bash is required to run Kafka shell scripts (their shebang is #!/bin/bash)." >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java is required on the server (JDK 17+). Install temurin-17-jdk or similar." >&2
  exit 1
fi

# Kafka bin/*.sh use #!/bin/bash; Linux has it, but FreeBSD only has bash under /usr/local/bin (no /bin/bash).
# Nested exec (kafka-storage.sh -> kafka-run-class.sh) uses the child script shebang again.
# Use the same bash path we already resolved (not #!/usr/bin/env bash: non-interactive SSH PATH may omit /usr/local/bin).
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
    {
      echo "#!${bash_exe}"
      tail -n +2 "$f"
    } >"$tmp" && mv "$tmp" "$f" && chmod a+x "$f"
  done < <(find "${KAFKA_HOME}/bin" -type f -name '*.sh' 2>/dev/null)
}

ARCHIVE="kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz"
DOWNLOAD_URL="https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/${ARCHIVE}"

mkdir -p "$INSTALL_ROOT"
cd "$INSTALL_ROOT"

if [ ! -f "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
  echo "Downloading $ARCHIVE ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$ARCHIVE" "$DOWNLOAD_URL"
  else
    wget -q -O "$ARCHIVE" "$DOWNLOAD_URL"
  fi
  tar -xzf "$ARCHIVE" -C "$INSTALL_ROOT"
  rm -f "$ARCHIVE"
  mv "$INSTALL_ROOT/kafka_${SCALA_VERSION}-${KAFKA_VERSION}" "$KAFKA_HOME"
fi

fix_kafka_bin_shebangs

mkdir -p "$(dirname "$CONFIG_PATH")" "$DATA_DIR/logs"

CLUSTER_UUID_FILE="$DATA_DIR/cluster.uuid"
if [ ! -f "$CLUSTER_UUID_FILE" ]; then
  # Invoke with explicit "bash": upstream scripts use #!/bin/bash; minimal systems may lack /bin/bash.
  CLUSTER_UUID="$(bash "$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)"
  echo "$CLUSTER_UUID" >"$CLUSTER_UUID_FILE"
fi
CLUSTER_UUID="$(cat "$CLUSTER_UUID_FILE")"

cat >"$CONFIG_PATH" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093

listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://127.0.0.1:9093
inter.broker.listener.name=PLAINTEXT
advertised.listeners=PLAINTEXT://${ADVERTISED_HOST}:9092
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
controller.listener.names=CONTROLLER

num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600

log.dirs=${DATA_DIR}/logs
num.partitions=2
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
default.replication.factor=1
min.insync.replicas=1
group.initial.rebalance.delay.ms=0
EOF

if [ ! -f "$DATA_DIR/logs/meta.properties" ]; then
  echo "Formatting KRaft storage..."
  bash "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CLUSTER_UUID" -c "$CONFIG_PATH" --ignore-formatted
fi

echo "Kafka KRaft ready. Config: $CONFIG_PATH"
echo "Control: $(cd "$(dirname "$0")" && pwd)/kafka-ctl.sh {start|stop|restart|status}"
