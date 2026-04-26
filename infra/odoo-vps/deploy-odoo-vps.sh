#!/usr/bin/env bash
# Запуск с CI (GitHub Actions, ubuntu) из корня репозитория. Нужны: odoo-addons/hh_process_eis, ~/.ssh/id_vps
# env: VPS_DEPLOY_USER, VPS_DEPLOY_HOST, VPS_DEPLOY_PORT, EIS_KEY_FOR_ODOO (тот же смысл, что APP_EIS_API_KEY),
#   ODOO_DATABASE_NAME (по умолчанию hh_process)
# На VPS: пользователь должен иметь sudo БЕЗ пароля (NOPASSWD) для apt, systemctl, rsync, odoo, tee в /etc/odoo
set -euo pipefail

: "${VPS_DEPLOY_USER:?VPS_DEPLOY_USER}"
: "${VPS_DEPLOY_HOST:?VPS_DEPLOY_HOST}"
: "${EIS_KEY_FOR_ODOO:?EIS_KEY_FOR_ODOO}"

VPSP="${VPS_DEPLOY_PORT:-22}"
ODOO_DB="${ODOO_DATABASE_NAME:-hh_process}"
EIS_FILE="${ODOO_EIS_ENV_FILE:-/etc/odoo/hhprocess-eis.env}"
HH_ADDON="/var/lib/odoo/hh-process-addons/hh_process_eis"
MARKER="/etc/odoo/.hh_eis_db_inited"
KEY="${VPS_SSH_KEY_FILE:-$HOME/.ssh/id_vps}"
REM="${VPS_DEPLOY_USER}@${VPS_DEPLOY_HOST}"
SSH_=(ssh -p "$VPSP" -i "$KEY" -o BatchMode=yes -o ConnectTimeout=30)
SCP_=(scp -P "$VPSP" -i "$KEY" -o BatchMode=yes)

if [ ! -f "$KEY" ]; then
  echo "Missing $KEY (set VPS_SSH_KEY in repo secrets for Prepare VPS SSH)" >&2
  exit 1
fi
if [ ! -d "odoo-addons/hh_process_eis" ]; then
  echo "Run from repo root; need odoo-addons/hh_process_eis" >&2
  exit 1
fi
if [ ! -f "infra/odoo-vps/install-odoo-ubuntu.sh" ]; then
  echo "Missing infra/odoo-vps/install-odoo-ubuntu.sh" >&2
  exit 1
fi

echo "## Sudo (NOPASSWD) check on VPS"
if ! "${SSH_[@]}" "$REM" "sudo -n true"; then
  echo "::error::На VPS у пользователя $VPS_DEPLOY_USER должен быть passwordless sudo (NOPASSWD) для docker-free установки Odoo (apt, systemctl, odoo, rsync, tee)."
  exit 1
fi

echo "## mkdir ~/hh-process/odoo-ci on VPS"
"${SSH_[@]}" "$REM" "mkdir -p ~/hh-process/odoo-ci"

echo "## scp install-odoo-ubuntu.sh"
"${SCP_[@]}" infra/odoo-vps/install-odoo-ubuntu.sh "${REM}:~/hh-process/odoo-ci/"

echo "## install-odoo-ubuntu.sh (apt, odoo 17, может занять несколько минут)"
# shellcheck disable=SC2029
"${SSH_[@]}" "$REM" "sudo -n env DEBIAN_FRONTEND=noninteractive bash ~/hh-process/odoo-ci/install-odoo-ubuntu.sh"

echo "## write $EIS_FILE (ODOO_EIS_API_KEY = Spring APP_EIS_API_KEY)"
{
  echo "# from CI"
  printf 'ODOO_EIS_API_KEY=%s\n' "$EIS_KEY_FOR_ODOO"
} | "${SSH_[@]}" "$REM" "sudo -n tee ${EIS_FILE} >/dev/null && sudo -n chown root:odoo ${EIS_FILE} && sudo -n chmod 640 ${EIS_FILE}"

echo "## rsync hh_process_eis -> ${HH_ADDON}"
rsync -az --delete -e "ssh -p $VPSP -i $KEY -o BatchMode=yes" \
  odoo-addons/hh_process_eis/ \
  "${REM}:~/hh-process/odoo-ci/hh_process_eis/"
# shellcheck disable=SC2029
"${SSH_[@]}" "$REM" "sudo -n rsync -a --delete ~/hh-process/odoo-ci/hh_process_eis/ ${HH_ADDON}/ && sudo -n chown -R odoo:odoo /var/lib/odoo/hh-process-addons"

echo "## first-time DB + modules (если нет ${MARKER})"
if ! "${SSH_[@]}" "$REM" "sudo -n test -f ${MARKER}"; then
  echo "Running odoo -i (first deploy) — может занять несколько минут"
  if ! "${SSH_[@]}" "$REM" "sudo -n -u odoo /usr/bin/odoo -c /etc/odoo/odoo.conf -d ${ODOO_DB} -i base,web,calendar,hh_process_eis --stop-after-init --without-demo=all"; then
    echo "::error::odoo -i failed — check logs on VPS: journalctl -u odoo -n 200"
    exit 1
  fi
  "${SSH_[@]}" "$REM" "sudo -n touch ${MARKER}"
else
  echo "DB already inited (marker present); restart only"
fi

echo "## systemctl restart odoo"
"${SSH_[@]}" "$REM" "sudo -n systemctl restart odoo"

echo "## health: TCP 8069 (до ~90с; bash /dev/tcp)"
# shellcheck disable=SC2029
if ! "${SSH_[@]}" "$REM" "bash" "-s" <<'REMOTE'
i=0
while [ "$i" -lt 30 ]; do
  (echo >/dev/tcp/127.0.0.1/8069) 2>/dev/null && exit 0
  sleep 3
  i=$((i+1))
done
exit 1
REMOTE
then
  echo "::warning::8069 not open in time; check: sudo systemctl status odoo, journalctl -u odoo"
  exit 1
fi
echo "Odoo on VPS: http://${VPS_DEPLOY_HOST}:8069"
