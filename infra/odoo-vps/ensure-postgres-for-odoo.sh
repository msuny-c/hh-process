#!/usr/bin/env bash
# PostgreSQL 15+: у роли public урезаны права; для Odoo БД и схема public должны принадлежать postgres-пользователю odoo.
# Запуск на VPS: sudo ./ensure-postgres-for-odoo.sh <имя_бд>   (после apt install postgresql odoo)
set -euo pipefail
DB="${1:?usage: $0 <database_name>}"

[ "$(id -u)" -eq 0 ] || { echo "Run as root: sudo $0 $DB" >&2; exit 1; }

if ! id odoo &>/dev/null; then
  echo "Linux user odoo not found; install the odoo package first." >&2
  exit 1
fi

# Роль `odoo` в PostgreSQL создаёт пакет `odoo` (Debian/Ubuntu)
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'odoo'" | grep -q 1; then
  echo "PostgreSQL role 'odoo' not found. Install the 'odoo' apt package first." >&2
  exit 1
fi

if sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB}'" | grep -q 1; then
  echo "Database ${DB} exists: set OWNER and fix public schema (PG 15+)"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER DATABASE \"${DB}\" OWNER TO odoo"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -d "$DB" -c "
    ALTER SCHEMA public OWNER TO odoo;
    GRANT ALL ON SCHEMA public TO odoo;
  "
else
  echo "CREATE DATABASE ${DB} OWNER=odoo"
  sudo -u postgres createdb -E UTF8 -O odoo -T template0 "$DB"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -d "$DB" -c "
    ALTER SCHEMA public OWNER TO odoo;
    GRANT ALL ON SCHEMA public TO odoo;
  "
fi
echo "PostgreSQL ready for: sudo -u odoo odoo -c /etc/odoo/odoo.conf -d ${DB} ..."
