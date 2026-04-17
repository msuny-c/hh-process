# HH Process

Spring Boot 3.4.3 сервис обработки откликов на вакансии под ЛР3 по БЛПС.

В проекте реализованы:

- HTTP Basic + XML users, без JWT.
- PostgreSQL + Flyway.
- Narayana JTA/XA.
- Асинхронный screening через Kafka.
- Асинхронные уведомления через Kafka.
- `@Scheduled` для timeout и export use cases.
- Вторая schedule DB с распределённой транзакцией на `invite/cancel interview`.
- Учебная JCA-интеграция с внешней EIS календаря собеседований.

## Архитектура

Один и тот же `jar` запускается в разных ролях через `APP_ROLE`:

- `api`: REST controllers, WebSocket endpoint, timeout scheduler, export scheduler, Kafka producer.
- `worker`: Kafka listeners для `application.submitted`, асинхронный screening.
- `eis-worker`: Kafka listener для `interview.export.requested`, JCA client и экспорт во внешнюю EIS.

Инфраструктура в `docker-compose.yml`:

- `postgres-main`
- `postgres-schedule`
- `zookeeper`
- `kafka`
- `app-api`
- `app-worker-1`
- `app-worker-2`
- `app-eis-worker`

## Бизнес-сценарии ЛР3

### Асинхронный screening

`POST /api/v1/candidates/vacancies/{vacancyId}`:

1. сохраняет `ApplicationEntity` со статусом `SCREENING_IN_PROGRESS`;
2. пишет history;
3. после commit публикует `application.submitted`;
4. worker получает событие через `@KafkaListener`;
5. выполняет screening и переводит заявку в:
   `ON_RECRUITER_REVIEW` или `SCREENING_FAILED`;
6. публикует `notification.requested`.

### Асинхронные уведомления

Сервисы публикуют `notification.requested` после commit.
Consumer создаёт запись в `notifications`; WebSocket push работает на узле, где включён notification consumer.

### Distributed transaction

Сценарий `invite interview` выполняется в одной JTA/XA-транзакции между:

- main DB: `applications`, `interviews`, `history`, `notifications`, ...
- schedule DB: `recruiter_schedule_slots`

Если резервирование слота падает, откатываются:

- статус заявки;
- запись интервью;
- запись в history;
- изменения в schedule DB.

### Scheduler use cases

- `TimeoutService` закрывает просроченные приглашения только на `api`.
- `InterviewExportScheduler` по cron ставит интервью в очередь на экспорт в EIS.

### JCA EIS

В пакете `ru.itmo.hhprocess.integration.eis.jca` реализован учебный resource adapter:

- `CalendarManagedConnectionFactory`
- `CalendarManagedConnection`
- `CalendarConnectionFactory`
- `CalendarConnection`
- `CalendarInteraction`
- `CalendarInteractionSpec`

Высокоуровневый клиент: `CalendarEisClient`.

## Основные переменные окружения

### Main DB

```text
POSTGRES_HOST=postgres-main
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_SCHEMA=public
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

### Schedule DB

```text
SCHEDULE_DB_HOST=postgres-schedule
SCHEDULE_DB_PORT=5432
SCHEDULE_DB_NAME=postgres
SCHEDULE_DB_SCHEMA=public
SCHEDULE_DB_USER=postgres
SCHEDULE_DB_PASSWORD=postgres
```

### App role / Narayana

```text
APP_ROLE=api|worker|eis-worker
APP_INSTANCE_NAME=hh-api
NARAYANA_NODE_IDENTIFIER=hh-api
NARAYANA_LOG_DIR=/app/transaction-logs
```

`NARAYANA_NODE_IDENTIFIER` должен быть уникальным для каждого инстанса.

### Kafka

```text
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
KAFKA_GROUP_ID=hh-process-screening
APP_SCREENING_ENABLED=true
APP_NOTIFICATIONS_ENABLED=true
APP_EIS_ENABLED=false
```

### Other

```text
APP_SECURITY_USERS_XML=/app/data/users.xml
WS_ALLOWED_ORIGINS=http://localhost:3000
APP_EXPORT_CRON=0 0 6 * * *
APP_EXPORT_LOOKAHEAD_HOURS=24
APP_SCHEDULE_DEBUG_FAIL_ON_RESERVE=false
```

## Безопасность

Используется HTTP Basic. Учётные данные сидовых пользователей лежат в `src/main/resources/security/users.xml`, а runtime XML-файл хранится по `APP_SECURITY_USERS_XML`.

Базовые пользователи:

- `admin@example.com / password123`
- `recruiter@example.com / password123`

## REST API

Базовый префикс: `/api/v1`

### Auth

- `POST /api/v1/auth/register/candidate`
- `GET /api/v1/me`

### Candidate

- `POST /api/v1/candidates/vacancies/{vacancyId}`
- `GET /api/v1/candidates/applications`
- `GET /api/v1/candidates/applications/{applicationId}`
- `POST /api/v1/candidates/applications/{applicationId}/invitation-response`

Создание отклика теперь возвращает промежуточный статус:

```json
{
  "application_id": "7a9c3c4d-6bdf-4a77-9f67-6f1db4fd74fe",
  "status": "SCREENING_IN_PROGRESS",
  "message": "Application accepted for asynchronous screening"
}
```

После этого клиент должен делать polling `GET /api/v1/candidates/applications/{applicationId}` или recruiter-view endpoint, пока статус не станет `ON_RECRUITER_REVIEW` либо `SCREENING_FAILED`.

### Recruiter

- `POST /api/v1/recruiters/vacancies`
- `GET /api/v1/recruiters/vacancies`
- `PATCH /api/v1/recruiters/vacancies/{vacancyId}/status`
- `POST /api/v1/recruiters/vacancies/{vacancyId}/close`
- `GET /api/v1/recruiters/applications`
- `GET /api/v1/recruiters/applications/{applicationId}`
- `POST /api/v1/recruiters/applications/{applicationId}/invite`
- `POST /api/v1/recruiters/applications/{applicationId}/reject`
- `GET /api/v1/recruiters/schedule`
- `POST /api/v1/recruiters/interviews/{interviewId}/cancel`

### Notifications

- `GET /api/v1/notifications`
- `PATCH /api/v1/notifications/{notificationId}/read`

### Admin / debug

- `POST /api/v1/admin/jobs/close-expired-invitations`
- `POST /api/v1/admin/jobs/export-interviews`
- `POST /api/v1/admin/debug/schedule-failure/{enabled}`

## Flyway

Main DB migrations: `src/main/resources/db/migration`

- `V6__kafka_processed_events.sql`
- `V7__interview_export_log.sql`
- `V8__application_async_flags.sql`

Schedule DB migrations: `src/main/resources/db/schedule-migration`

- `S1__schedule_schema.sql`

## Локальный запуск

```bash
docker compose down -v
docker compose up --build
```

Порты:

- API: `http://localhost:8080`
- Kafka host listener: `localhost:29092`
- main DB: `localhost:5432`
- schedule DB: `localhost:5433`

Обе PostgreSQL БД запускаются с `max_prepared_transactions=100`, что обязательно для XA.

## Тестовые и демонстрационные сценарии

- Асинхронный screening: `POST apply` -> polling `GET application`.
- Distributed transaction rollback: включить `POST /api/v1/admin/debug/schedule-failure/true`, затем вызвать `invite`.
- Export to EIS: создать интервью, вызвать `POST /api/v1/admin/jobs/export-interviews`, дождаться обработки `app-eis-worker`.

## Важные ограничения реализации

- XA между Kafka и PostgreSQL не используется.
- WebSocket push остаётся локальным для инстанса, где включён notification consumer.
- Kafka idempotency реализована через таблицу `processed_kafka_events`.
