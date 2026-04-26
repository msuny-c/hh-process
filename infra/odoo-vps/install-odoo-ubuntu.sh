#!/usr/bin/env bash
# Odoo 17 (Community) + PostgreSQL: установка пакетом из репозитория nightly (Ubuntu 20.04+ / Debian 11+).
# Запуск: sudo ./install-odoo-ubuntu.sh
# После: скопируйте каталог `odoo-addons/hh_process_eis` (или весь `odoo-addons`) в ${HH_ADDONS_DIR},
#   затем: sudo -u odoo /usr/bin/odoo -c /etc/odoo/odoo.conf -d <имя_бд> -i base,web,calendar,hh_process_eis --stop-after-init --without-demo=all
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run as root: sudo $0" >&2; exit 1; }

ODOO_SERIES="${ODOO_SERIES:-17.0}"
HH_ADDONS_DIR="${HH_ADDONS_DIR:-/var/lib/odoo/hh-process-addons}"
EIS_ENV_FILE="${EIS_ENV_FILE:-/etc/odoo/hhprocess-eis.env}"
ODOO_CONF="${ODOO_CONF:-/etc/odoo/odoo.conf}"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg lsb-release postgresql wkhtmltopdf xfonts-75dpi xfonts-base

install -d -m 0755 /usr/share/keyrings
if [ ! -f /usr/share/keyrings/odoo-nightly.gpg ]; then
  curl -fsSL "https://nightly.odoo.com/odoo.key" | gpg --dearmor -o /usr/share/keyrings/odoo-nightly.gpg
fi
echo "deb [signed-by=/usr/share/keyrings/odoo-nightly.gpg] http://nightly.odoo.com/${ODOO_SERIES}/nightly/deb/ ./" \
  > /etc/apt/sources.list.d/odoo-nightly.list
apt-get update -qq
apt-get install -y odoo

install -d -m 0755 -o odoo -g odoo "$HH_ADDONS_DIR"

# Доп. каталог аддонов; ключ API — /etc/odoo/hhprocess-eis.env + drop-in systemd.
if [ ! -f "$ODOO_CONF" ]; then
  echo "Expected $ODOO_CONF from package odoo — not found" >&2
  exit 1
fi
if ! grep -q "hh-process-addons" "$ODOO_CONF" 2>/dev/null; then
  cp -a "$ODOO_CONF" "${ODOO_CONF}.bak.$(date +%Y%m%d%H%M%S)"
  if grep -qE '^[[:space:]]*addons_path[[:space:]]*=' "$ODOO_CONF"; then
    sed -i "s|^\([[:space:]]*addons_path[[:space:]]*=[[:space:]]*\)\(.*\)$|\1\2,${HH_ADDONS_DIR}|" "$ODOO_CONF"
  else
    {
      echo ""
      echo "# hh-process EIS (hh_process_eis)"
      echo "addons_path = /usr/lib/python3/dist-packages/odoo/addons,${HH_ADDONS_DIR}"
    } >> "$ODOO_CONF"
  fi
fi

if [ ! -f "$EIS_ENV_FILE" ]; then
  umask 026
  {
    echo "# Ключ X-API-Key (как в Spring APP_EIS_API_KEY)"
    echo "ODOO_EIS_API_KEY=change-me"
  } > "$EIS_ENV_FILE"
  chown root:odoo "$EIS_ENV_FILE"
  chmod 640 "$EIS_ENV_FILE"
  echo "Created $EIS_ENV_FILE (edit ODOO_EIS_API_KEY, chgrp odoo)"
fi

install -d -m 0755 /etc/systemd/system/odoo.service.d
cat > /etc/systemd/system/odoo.service.d/hhprocess-eis.conf <<UNIT
[Service]
EnvironmentFile=-${EIS_ENV_FILE}
UNIT
systemctl daemon-reload
systemctl enable --now odoo 2>/dev/null || true

echo ""
echo "OK: Odoo service enabled. Addons: ${HH_ADDONS_DIR}"
echo "1) rsync or copy repo odoo-addons/hh_process_eis -> ${HH_ADDONS_DIR}/"
echo "2) sudo chown -R odoo:odoo ${HH_ADDONS_DIR}"
echo "3) sudo -u odoo /usr/bin/odoo -c $ODOO_CONF -d YOUR_DB -i base,web,calendar,hh_process_eis --stop-after-init --without-demo=all"
echo "4) systemctl restart odoo"
echo "5) In Spring: APP_EIS_REMOTE_BASE_URL=http://<VPS_IP_or_DNS>:8069  (and matching APP_EIS_API_KEY if used)"
