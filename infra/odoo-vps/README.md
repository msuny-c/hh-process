# EIS (Odoo) на внешнем VPS без Docker

Сервис **не** в контейнерах: пакет **Odoo 17** из [nightly (deb)](https://nightly.odoo.com/), **PostgreSQL** и модуль `hh_process_eis` из каталога `odoo-addons/` в корне репозитория.

## CI (`.github/workflows/deploy.yml`)

Один и тот же **VPS** и **те же** credentials, что и для обратного SSH-туннеля к Postgres: `VPS_SSH_HOST`, `VPS_SSH_USER`, `VPS_SSH_PORT`, `VPS_SSH_KEY` (secret). Шаг *Prepare VPS SSH* пишет `~/.ssh/id_vps` на раннере; далее *Sync Odoo EIS add-on to VPS* делает `rsync` в `~/VPS_ODOO_ADDONS_RPATH/hh_process_eis/` (по умолчанию `~/hh-process/odoo-addons/hh_process_eis`). Отключение: `SKIP_ODOO_VPS_ADDONS_SYNC=true`.

Если не заданы `APP_EIS_REMOTE_BASE_URL` и `ODOO_VPS_PUBLIC_BASE_URL`, в `APP_EIS_REMOTE_BASE_URL` подставляется `http://<VPS_SSH_HOST>:8069` (тот же хост).

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

## Обновления модуля

После изменений в `odoo-addons/hh_process_eis` снова выполните `rsync` и при необходимости обновление модуля в Odoo (`-u hh_process_eis`) или рестарт сервиса, если менялся только Python/XML.

## Переменные скрипта (опционально)

| Env | Смысл |
|-----|--------|
| `ODOO_SERIES` | ветка репо nightly, по умолчанию `17.0` |
| `HH_ADDONS_DIR` | каталог для кастомных аддонов, по умолчанию `/var/lib/odoo/hh-process-addons` |
| `EIS_ENV_FILE` | файл с `ODOO_EIS_API_KEY`, по умолчанию `/etc/odoo/hhprocess-eis.env` |
