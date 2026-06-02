#!/bin/sh
set -eu


PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-"$PROJECT_DIR/target/helios-freebsd-bundle"}
ARCHIVE=${ARCHIVE:-"$PROJECT_DIR/target/hh-process-helios-freebsd.tar.gz"}

WAR=${WAR:-"$PROJECT_DIR/target/ROOT.war"}
if [ ! -f "$WAR" ]; then
  echo "WAR not found: $WAR" >&2
  echo "Run: mvn clean package -DskipTests" >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/infra/helios" "$OUT_DIR/data"

cp "$WAR" "$OUT_DIR/ROOT.war"
cp "$PROJECT_DIR/infra/appctl.sh" "$OUT_DIR/appctl.sh"
cp "$PROJECT_DIR/infra/helios/app.env.example" "$OUT_DIR/app.env.example"
cp "$PROJECT_DIR/infra/helios/check-freebsd.sh" "$OUT_DIR/check-freebsd.sh"
cp "$PROJECT_DIR/infra/helios/start-wildfly-freebsd.sh" "$OUT_DIR/start-wildfly-freebsd.sh"
cp "$PROJECT_DIR/README-HELIOS-FREEBSD.md" "$OUT_DIR/README-HELIOS-FREEBSD.md"
cp "$PROJECT_DIR/src/main/resources/security/users.xml" "$OUT_DIR/data/users.xml"
chmod +x "$OUT_DIR/appctl.sh" "$OUT_DIR/check-freebsd.sh" "$OUT_DIR/start-wildfly-freebsd.sh"

( cd "$OUT_DIR/.." && tar -czf "$ARCHIVE" "$(basename "$OUT_DIR")" )

echo "Created: $ARCHIVE"
