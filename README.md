# HH Process — сервис обработки откликов на вакансии

Spring Boot + WildFly сервис для обработки жизненного цикла откликов на вакансии. Бизнес-процесс вынесен в standalone Camunda BPM: пользовательские действия демонстрируются через Camunda Tasklist/Forms, решения ролей и статусов вынесены в DMN, а Java-код работает как адаптер external tasks, task listeners, транзакций и интеграции с БД.

Текущий демонстрационный контур:

- `postgres` хранит данные приложения и Camunda в разных схемах;
- `camunda` запускает BPMN/DMN, Tasklist, Cockpit и Forms;
- `app` деплоится как `ROOT.war` в WildFly, синхронизирует пользователей/группы Camunda, назначает user tasks и обрабатывает external tasks;
- валидация Camunda Forms выполняется server-side во внешнем Java-адаптере, затем возвращает пользователя на форму через BPMN error loop `FORM_VALIDATION_FAILED`.

## Конфигурация

Список используемых переменных окружения (берутся из `.env`):

```text
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_SCHEMA=public
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres

WS_ALLOWED_ORIGINS=http://localhost:3000 (через запятую)
```

Сервер слушает порт `8080`.

## Авторизация (HTTP Basic)

Все защищенные endpoint'ы используют HTTP Basic:

```bash
curl -u recruiter@example.com:password123 http://localhost:8080/api/v1/me
```

Роли определяют доступ к группам API:
- `/api/v1/candidates/**` -> `CANDIDATE`
- `/api/v1/recruiters/**` -> `RECRUITER`
- `/api/v1/admin/**` -> `ADMIN`

## REST API

REST API остаётся служебным уровнем приложения и используется автотестами, но основной демонстрационный путь лабораторной проходит через Camunda Tasklist и Camunda Forms. Для защиты см. `CAMUNDA_README.md` и `docs/CAMUNDA_TASKLIST_DEMO.md`.

Camunda identity/users/groups/filters синхронизируются внешним `CamundaIdentityProviderService`, а активные user tasks назначаются владельцам через `CamundaTaskListenerAdapter`. Server-side ошибки форм проходят через `CamundaFormValidator` и BPMN loop `FORM_VALIDATION_FAILED -> исходная user task`; в переменные процесса попадают `formErrorMessage`, `formErrorField`, `formErrorFields`, `formErrorCode`, поэтому форма показывает понятную ошибку и проблемное поле. При старте приложения создаётся новый Camunda deployment из текущего WAR, чтобы Cockpit/Tasklist показывали актуальные BPMN/DMN.

Базовый префикс: `/api/v1`

### Auth

- `POST /api/v1/auth/register/candidate` (регистрация кандидата)
- `GET /api/v1/me` (профиль текущего пользователя)

Пример регистрации кандидата:

```bash
curl -sS -X POST http://localhost:8080/api/v1/auth/register/candidate \
  -H 'Content-Type: application/json' \
  -d '{"email":"candidate@example.com","password":"password123","first_name":"Candidate","last_name":"Demo"}'
```

### Кандидат

- `POST /api/v1/candidates/vacancies/{vacancyId}` (подача заявки)
- `GET /api/v1/candidates/applications` (свои заявки)
- `GET /api/v1/candidates/applications/{applicationId}` (заявка по id)
- `POST /api/v1/candidates/applications/{applicationId}/invitation-response` (ответ на приглашение)

Тела запросов:

`CreateApplicationRequest`:
```json
{ "resume_text": "...", "cover_letter": "..." }
```

`InvitationResponseRequest`:
```json
{ "response_type": "ACCEPT|DECLINE|OTHER", "message": "..." }
```

### Рекрутер

- `POST /api/v1/recruiters/vacancies` (создать вакансию)
- `GET /api/v1/recruiters/vacancies` (свои вакансии)
- `PATCH /api/v1/recruiters/vacancies/{vacancyId}/status` (обновить статус)
- `GET /api/v1/recruiters/applications` (заявки по вакансиям; фильтры `status` и `vacancy_id`)
- `GET /api/v1/recruiters/applications/{applicationId}` (заявка по id)
- `POST /api/v1/recruiters/applications/{applicationId}/reject` (отклонить заявку)
- `POST /api/v1/recruiters/applications/{applicationId}/invite` (пригласить кандидата)

Тело запроса `CreateVacancyRequest`:
```json
{ "title":"...", "description":"...", "required_skills":["..."], "screening_threshold": 75 }
```

Тело `UpdateVacancyStatusRequest`:
```json
{ "status": "ACTIVE|CLOSED" }
```

Тело `RejectRequest`:
```json
{ "comment":"..." }
```

Тело `InviteRequest`:
```json
{ "message":"..." }
```

### Уведомления

- `GET /api/v1/notifications` (список уведомлений текущего пользователя)
- `PATCH /api/v1/notifications/{notificationId}/read` (пометить прочитанным)

### Admin

- `POST /api/v1/admin/jobs/close-expired-invitations` (закрыть просроченные приглашения)

## Данные и схема БД

Миграции лежат в `src/main/resources/db/migration` и выполняются Flyway при старте.

Основные таблицы:
- `users`, `roles`, `user_roles`
- `vacancies`
- `applications`
- `screening_results`
- `invitation_responses`
- `notifications`
- `application_status_history`


## JTA / Narayana notes

- Declarative transaction management is implemented with `@Transactional` in the service layer.
- JTA transaction manager is `Narayana` (`dev.snowdrop:narayana-spring-boot-starter`).
- Narayana object store logs are written to `transaction-logs/` by default.
- For Docker, the directory is mounted as a named volume.
- For native запуск on the server, `infra/appctl.sh` creates the log directory before starting the JAR and passes `-Dnarayana.log-dir=...`.
- `NARAYANA_NODE_IDENTIFIER` should be unique per instance if you ever run more than one app instance against the same resources.
- PostgreSQL **must** be started with `max_prepared_transactions > 0`, otherwise XA/JTA transactions fail during the prepare phase with `ERROR: prepared transactions are disabled`.
- For Docker and CI, PostgreSQL is also started with `max_connections=200` so WildFly, Narayana and standalone Camunda have enough room for parallel startup and smoke tests.


## Локальный прогон в Docker

Если вы раньше уже поднимали проект через Docker, база может остаться в named volume `postgres_data`.
Из-за этого локальные тесты могут падать не из-за кода, а из-за старых данных: отсутствуют роли, сидовые пользователи или старые пароли.

Для полностью чистого прогона:

```bash
docker compose down -v
docker compose up --build
```

Быстрая проверка после старта:

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8081/engine-rest/engine
```

В `docker-compose.yml` PostgreSQL уже запускается с `max_prepared_transactions=200` и `max_connections=200`, поэтому JTA/Narayana и локальные Docker smoke-тесты работают из коробки.

Начиная с миграции `V4__repair_seed_data.sql`, приложение при старте дополнительно восстанавливает базовые роли и сидовых пользователей:
- `admin@example.com`
- `recruiter@example.com`

Это помогает и для локального Docker, и для CI/CD, если база уже существовала ранее.


## Native / CI/CD checklist for PostgreSQL

Для любого окружения без Docker у PostgreSQL должна быть включена поддержка prepared transactions. Минимум:

```conf
max_prepared_transactions = 200
max_connections = 200
```

После изменения параметра нужен restart PostgreSQL. Без этого приложение стартует, но первые write-операции под JTA будут падать на commit/prepare.


## Composite transactional flows

Additional endpoints:
- `POST /api/v1/recruiters/interviews/{interviewId}/cancel`
- `POST /api/v1/recruiters/vacancies/{vacancyId}/close`
- `GET /api/v1/recruiters/schedule?weekOffset=0`

`POST /api/v1/recruiters/applications/{applicationId}/invite` now also supports optional `scheduledAt` and `durationMinutes`. When they are omitted, defaults are used so older tests remain compatible.


## Python API tests

В каталоге `test/` лежат интеграционные и e2e-сценарии для REST API, Camunda Tasklist/Form path, DMN runtime, BPMN visual contract и транзакционных сценариев.

Основные команды:

```bash
python test/test.py
python test/test_composite_transactions.py
python test/test_security_validation.py
python test/test_access_matrix.py
python test/test_transaction_atomicity.py
python test/test_business_rules.py
python test/test_timeout_job_db_fixture.py
python test/test_camunda_model_coverage.py
python test/test_camunda_visual_model_contract.py
python test/test_camunda_decisions_runtime.py
python test/test_camunda_tasklist_candidate_apply.py
python test/test_camunda_smoke_flow.py
python test/test_admin_interview_reset.py
python test/test_camunda_integration.py
python test/test_camunda_scenarios.py
python test/test_camunda_e2e.py
```

Полный Docker-прогон против поднятого compose-окружения:

```bash
docker build -t hh-process-tests:local test
docker run --rm --network hh-process_default \
  -e BASE_URL=http://app:8080 \
  -e CAMUNDA_URL=http://camunda:8080/engine-rest \
  -e POSTGRES_HOST=postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_SCHEMA=public \
  -v "$PWD:/workspace" \
  -w /workspace \
  hh-process-tests:local \
  sh -c 'set -eu; for f in test/test_camunda_model_coverage.py test/test_camunda_visual_model_contract.py test/test_camunda_decisions_runtime.py test/test_camunda_tasklist_candidate_apply.py test/test.py test/test_camunda_smoke_flow.py test/test_composite_transactions.py test/test_security_validation.py test/test_access_matrix.py test/test_transaction_atomicity.py test/test_business_rules.py test/test_timeout_job_db_fixture.py test/test_admin_interview_reset.py test/test_camunda_integration.py test/test_camunda_scenarios.py test/test_camunda_e2e.py; do echo "===== $f"; python "$f"; done'
```

Java unit tests также можно запускать в контейнере:

```bash
docker run --rm --network hh-process_default \
  -v "$PWD:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-17 mvn test -B
```

Camunda-focused проверки:
- `test/test_camunda_model_coverage.py` — BPMN/DMN coverage: DI, DMN decisions, messages, compensation, отсутствие fallback scheduler.
- `test/test_camunda_visual_model_contract.py` — визуальный контракт BPMN: каждый файл имеет pool, lane, подписанные start/end, BPMNShape/BPMNEdge и открывается как диаграмма.
- `test/test_camunda_decisions_runtime.py` — runtime evaluation DMN через Camunda REST.
- `test/test_camunda_tasklist_candidate_apply.py` — кандидат создаёт отклик через Camunda Form/Tasklist path, не через REST apply endpoint.
- `test/test_camunda_smoke_flow.py` — сквозной Camunda smoke: users/groups, instances, history activities, UI-процессы, variables и отсутствие incidents.
- `test/test_admin_interview_reset.py`, `test/test_camunda_integration.py`, `test/test_camunda_scenarios.py`, `test/test_camunda_e2e.py` — полные runtime-сценарии Camunda, административный reset, deployed artifacts, authorizations, scheduler и e2e happy path.


## Локальный запуск через WildFly в Docker

Приложение теперь собирается в `WAR` и деплоится в WildFly-контейнер, а PostgreSQL поднимается отдельным сервисом в `docker-compose.yml`.

```bash
docker compose up --build
```

API остаётся доступным от корня:

```text
http://localhost:8080/api/v1/...
```

Для полностью чистого запуска:

```bash
docker compose down -v
docker compose up --build
```

Подробности по связке Camunda + WildFly см. в `CAMUNDA_README.md` и `HELIOS_SPLIT_DEPLOY.md`.


## WildFly / FreeBSD / Helios

Docker Compose используется для локальной разработки. Для Helios / FreeBSD добавлена split-схема:

```text
HELIOS_SPLIT_DEPLOY.md
infra/helios-split/camundactl.sh
infra/helios-split/wildflyctl.sh
scripts/release/make-helios-freebsd-bundle.sh
```

Сборка WAR:

```bash
mvn clean package -DskipTests
```

Сборка архива для Helios:

```bash
./scripts/release/make-helios-freebsd-bundle.sh
```


---

## Camunda / BPMN quick start

Подробная инструкция по запуску Camunda-процессов, ролям, входу в Tasklist, просмотру выполнения на диаграмме и демонстрационному сценарию находится в отдельном файле:

```text
CAMUNDA_README.md
```

Для защиты лабораторной см. особенно разделы `13` и `14` в `CAMUNDA_README.md`: там собран пошаговый сценарий показа преподавателю, проверки ролей, DMN-матрицы прав, call activity уведомлений, транзакций/откатов с compensation/cancel events, Camunda scheduler, pool/lane layout BPMN и сноски на Java/BPMN-код.

## Helios split deployment

Detailed deployment guide for the required split setup — WildFly on one Helios machine, standalone Camunda on another Helios machine, PostgreSQL on VPS — is available in [`HELIOS_SPLIT_DEPLOY.md`](HELIOS_SPLIT_DEPLOY.md).

> Helios split deploy is now restricted to the `lab4` branch and stops previous WildFly/Camunda/tunnel tasks before restarting. See `HELIOS_SPLIT_DEPLOY.md`.
