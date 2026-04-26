# EIS (Odoo) на внешнем VPS без Docker

Сервис **не** в контейнерах: пакет **Odoo 17** из [nightly (deb)](https://nightly.odoo.com/), **PostgreSQL** и модуль `hh_process_eis` из каталога `odoo-addons/` в корне репозитория.

## CI (`.github/workflows/deploy.yml`)

Тот же **VPS** и `VPS_SSH_*` + `VPS_SSH_KEY`, что и для туннеля к Postgres. Шаг *Deploy Odoo on VPS* вызывает [deploy-odoo-vps.sh](deploy-odoo-vps.sh): заливает [install-odoo-ubuntu.sh](install-odoo-ubuntu.sh), выполняет `sudo` установку (apt, пакет Odoo), пишет `ODOO_EIS_API_KEY` в `/etc/odoo/hhprocess-eis.env` (тот же ключ, что `EIS_KEY_FOR_ODOO` / `APP_EIS_API_KEY` / `EIS_API_KEY` в repository secrets или variables), копирует `hh_process_eis` в `/var/lib/odoo/hh-process-addons`, при первом деплое — `-i` модулей в БД (имя: `ODOO_DATABASE_NAME`, по умолчанию `hh_process`), `systemctl restart odoo`, проверка порта 8069.

**На VPS** пользователю из `VPS_SSH_USER` нужен **passwordless sudo** (`/etc/sudoers.d/...` с `NOPASSWD:ALL` или точечно под команды) — иначе CI упадёт на `sudo -n`. Отключить весь шаг: repository variable `SKIP_ODOO_VPS=true` или `1`.

Если не заданы `APP_EIS_REMOTE_BASE_URL` и `ODOO_VPS_PUBLIC_BASE_URL`, в `APP_EIS_REMOTE_BASE_URL` на helios подставляется `http://<VPS_SSH_HOST>:8069`.

## Установка (Ubuntu/Debian, root)

```bash
sudo bash infra/odoo-vps/install-odoo-ubuntu.sh
```

Скрипт: репозиторий `17.0/nightly/deb`, пакет `odoo`, каталог аддонов по умолчанию `/var/lib/odoo/hh-process-addons`, файл с ключом `/etc/odoo/hhprocess-eis.env` (переменная `ODOO_EIS_API_KEY` — тот же смысл, что `APP_EIS_API_KEY` в Spring), drop-in к `odoo.service` для подхвата env.

## Модуль и первичная БД

1. Скопируйте аддон на сервер (каталог `hh_process_eis` внутри `odoo-addons`):

   ```bash
   rsync -a ./odoo-addons/hh_process_eis/ root@vps:/var/lib/odoo/hh-process-addons/hh_process_eis/
   sudo chown -R odoo:odoo /var/lib/odoo/hh-process-addons
   ```

2. Правка ключа: `sudo nano /etc/odoo/hhprocess-eis.env` (и то же значение в GitHub/на helios: `APP_EIS_API_KEY` / `EIS_API_KEY` в переменных).

3. Создать БД и установить модули (один раз, под пользователем `odoo`):

   ```bash
   sudo -u odoo /usr/bin/odoo -c /etc/odoo/odoo.conf -d hh_process -i base,web,calendar,hh_process_eis --stop-after-init --without-demo=all
   sudo systemctl restart odoo
   ```

4. В приложении на helios задайте (repository variables):

   - `APP_EIS_REMOTE_BASE_URL` — публичный URL Odoo, например `http://<vps-адрес>:8069` (или `https://…` за reverse proxy).
   - при использовании ключа: согласованные `APP_EIS_API_KEY` и `ODOO_EIS_API_KEY` в `/etc/odoo/hhprocess-eis.env`.

### `permission denied for schema public` (PostgreSQL 15+)

Пакет Odoo часто подключается к БД, где владелец `public` не совпадает с пользователем `odoo`. Скрипт [ensure-postgres-for-odoo.sh](ensure-postgres-for-odoo.sh) выставляет `ALTER DATABASE … OWNER`, `ALTER SCHEMA public OWNER TO odoo` (его вызывают `install-odoo-ubuntu.sh` при наличии файла рядом и [deploy-odoo-vps.sh](deploy-odoo-vps.sh) перед `odoo -i`).

Если `odoo -i` **уже один раз падал** и в БД остались кривые объекты, на VPS (осторожно, удалит БД):

```bash
sudo -u postgres dropdb ИМЯ_БД
sudo rm -f /etc/odoo/.hh_eis_db_inited
```

затем снова deploy или: `sudo bash ~/hh-process/odoo-ci/ensure-postgres-for-odoo.sh ИМЯ_БД` и `sudo -u odoo odoo -c /etc/odoo/odoo.conf -d ИМЯ_БД -i base,web,calendar,hh_process_eis --stop-after-init --without-demo=all`

## Обновления модуля

После изменений в `odoo-addons/hh_process_eis` снова выполните `rsync` и при необходимости обновление модуля в Odoo (`-u hh_process_eis`) или рестарт сервиса, если менялся только Python/XML.

## Переменные скрипта (опционально)

| Env | Смысл |
|-----|--------|
| `ODOO_SERIES` | ветка репо nightly, по умолчанию `17.0` |
| `HH_ADDONS_DIR` | каталог для кастомных аддонов, по умолчанию `/var/lib/odoo/hh-process-addons` |
| `EIS_ENV_FILE` | файл с `ODOO_EIS_API_KEY`, по умолчанию `/etc/odoo/hhprocess-eis.env` |
