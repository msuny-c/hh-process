# HH Process

Spring Boot 3.4.3 сервис обработки откликов на вакансии под ЛР3 по БЛПС.

В проекте реализованы:

- HTTP Basic + XML users, без JWT.
- PostgreSQL + Flyway (одна БД: заявки, интервью, слоты расписания, уведомления, служебные таблицы).
- Narayana JTA и XA-драйвер PostgreSQL для основной БД.
- Асинхронный screening через Kafka: worker считает результат по `application.submitted` и публикует `application.screened`; запись в БД (`screening_results`, смена статуса заявки, уведомления) делает consumer на **`api`**.
- Идемпотентность Kafka consumers через таблицу `processed_kafka_events`.
- Уведомления: запись в БД и WebSocket push после commit транзакции (без отдельного Kafka-топика).
- `@Scheduled` для timeout-закрытия приглашений и планового экспорта интервью в EIS.
- Отдельный HTTP-сервис **`external-eis/`** (внешняя EIS); **`api`** вызывает его через JCA-адаптер (`CalendarManagedConnectionFactory`), без Kafka.

## Архитектура

Один и тот же `jar` запускается в разных ролях через `APP_ROLE`:

- `api`: REST, WebSocket, Swagger UI, schedulers (timeout, **прямой экспорт в EIS через JCA** после commit), Kafka consumer на `application.screened`.
- `worker`: Kafka listener на `application.submitted` (только расчёт screening и публикация `application.screened`, без записи результата в БД); `APP_SCREENING_ENABLED=true`.

В `docker-compose.yml` поднимается:

- `postgres-main` — единственная PostgreSQL.
- `zookeeper`, `kafka` — брокер (внутри сети `kafka:9092`, с хоста `localhost:29092`).
- `kafka-topics-init` — однократное создание топиков.
- `kafka-ui` — веб-интерфейс к Kafka (см. порты ниже).
- **`external-eis`** — внешний учебный EIS (HTTP, порт 8090).
- `app-api`, `app-worker` — два контейнера основного приложения с разными `APP_ROLE`.

Топики Kafka (имена по умолчанию, переопределяются через `APP_TOPIC_*`):

- `application.submitted` — отклик принят, нужен screening.
- `application.screened` — результат screening (payload с score, matched skills и т.д.); публикует **worker**, в БД применяет **api**.

## Бизнес-сценарии ЛР3

### Асинхронный screening

`POST /api/v1/candidates/vacancies/{vacancyId}`:

1. сохраняет `ApplicationEntity` со статусом `SCREENING_IN_PROGRESS`;
2. пишет history;
3. после commit публикует `application.submitted`;
4. worker получает событие, **без записи в БД** считает screening и публикует `application.screened`;
5. **api** (`ApplicationScreenedConsumer`) сохраняет `ScreeningResultEntity`, обновляет заявку (`AsyncScreeningResultService`);
6. уведомления рекрутеру/кандидату — после commit на **api** (`NotificationAfterCommitService` → `NotificationService` + WebSocket).

### Уведомления

После commit транзакции вызывается `NotificationAfterCommitService`: запись в `notifications` и push через `WebSocketNotificationService` (только на процессах `api`). Уведомления по завершении screening создаются вместе с пунктом 5 выше.

### Транзакционная целостность приглашения на интервью

Сценарий `invite interview` обновляет в **одной** БД заявку, интервью и таблицу `recruiter_schedule_slots`. При ошибке (в т.ч. при включённом debug `fail-on-reserve`) откатывается вся транзакция — без второй отдельной БД расписания.

### Scheduler use cases

- `TimeoutService` закрывает просроченные приглашения на роли `api`.
- `InterviewExportScheduler` по cron ставит интервью на экспорт: после commit транзакции вызывается `InterviewExportService` → JCA → HTTP к **`external-eis`** (без Kafka).

### JCA EIS

В пакете `ru.itmo.hhprocess.integration.eis.jca` — учебный resource adapter; при заданном **`APP_EIS_REMOTE_BASE_URL`** операции CREATE/CANCEL/GET уходят на внешний сервис по HTTP. Если URL пустой, используется in-memory хранилище внутри JVM (удобно для тестов без `external-eis`).

- `CalendarManagedConnectionFactory`, `CalendarManagedConnection`, `CalendarConnectionFactory`, `CalendarConnection`, `CalendarInteraction`, `CalendarInteractionSpec`

Высокоуровневый клиент: `CalendarEisClient`.

## Основные переменные окружения

### PostgreSQL (основная БД)

```text
POSTGRES_HOST=postgres-main
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_SCHEMA=public
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

### App role / Narayana

```text
APP_ROLE=api|worker
APP_INSTANCE_NAME=hh-api
NARAYANA_NODE_IDENTIFIER=hh-api
NARAYANA_LOG_DIR=/app/transaction-logs
```

`NARAYANA_NODE_IDENTIFIER` должен быть уникальным для каждого инстанса.

### Kafka

Пример для **worker** (screening):

```text
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
KAFKA_GROUP_ID=hh-process-worker
APP_SCREENING_ENABLED=true
```

Для **api** обычно `APP_SCREENING_ENABLED=false`; у API нужен Kafka для consumer `application.screened`. В `docker-compose` у API и worker разные `KAFKA_GROUP_ID`.

### Внешняя EIS (HTTP)

```text
APP_EIS_REMOTE_BASE_URL=http://external-eis:8090
```

Пустое значение — режим in-memory внутри JCA (без отдельного сервиса).

Имена топиков (опционально):

```text
APP_TOPIC_APPLICATION_SUBMITTED=application.submitted
APP_TOPIC_APPLICATION_SCREENED=application.screened
```

### Прочее

```text
SERVER_PORT=8080
APP_SECURITY_USERS_XML=/app/data/users.xml
WS_ALLOWED_ORIGINS=http://localhost:3000
APP_EXPORT_CRON=0 0 6 * * *
APP_EXPORT_LOOKAHEAD_HOURS=24
APP_SCHEDULE_DEBUG_FAIL_ON_RESERVE=false
APP_TIMEOUT_CHECK_INTERVAL_MS=60000
APP_TIMEOUT_INITIAL_DELAY_MS=30000
```

## Безопасность

Используется HTTP Basic. Учётные данные сидовых пользователей лежат в `src/main/resources/security/users.xml`, а runtime XML-файл задаётся через `APP_SECURITY_USERS_XML`.

Базовые пользователи:

- `admin@example.com / password123` — админские job/debug endpoint’ы (`/api/v1/admin/...`).
- `recruiter@example.com / password123`

## REST API

Базовый префикс: `/api/v1`. Документация OpenAPI: Swagger UI по пути `/swagger-ui.html` (см. `springdoc` в `application.yml`).

### Auth

- `POST /api/v1/auth/register/candidate`
- `GET /api/v1/me`

### Candidate

- `POST /api/v1/candidates/vacancies/{vacancyId}`
- `GET /api/v1/candidates/applications`
- `GET /api/v1/candidates/applications/{applicationId}`
- `POST /api/v1/candidates/applications/{applicationId}/invitation-response`

Создание отклика возвращает статус для кандидата «заявка подана» (внутри до завершения screening хранится `SCREENING_IN_PROGRESS`):

```json
{
  "application_id": "7a9c3c4d-6bdf-4a77-9f67-6f1db4fd74fe",
  "status": "APPLICATION_SUBMITTED",
  "message": "Application submitted"
}
```

Далее клиент опрашивает `GET /api/v1/candidates/applications/{applicationId}`, пока статус не станет `ON_RECRUITER_REVIEW` либо `SCREENING_FAILED` (пока идёт скрининг, в ответе кандидату по-прежнему `APPLICATION_SUBMITTED`).

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

Требуется роль admin и соответствующие привилегии:

- `POST /api/v1/admin/jobs/close-expired-invitations`
- `POST /api/v1/admin/jobs/export-interviews`
- `POST /api/v1/admin/debug/schedule-failure/{enabled}`

## Flyway

Миграции основной БД: `src/main/resources/db/migration` (`V1` … `V8` — см. файлы в каталоге).

## Локальный запуск

```bash
docker compose down -v
docker compose up --build
```

Порты:

- API и Actuator: `http://localhost:8080` (health: `/actuator/health`)
- Внешняя EIS: `http://localhost:8090` (health: `/actuator/health`)
- Kafka UI: `http://localhost:8081`
- Kafka с хоста: `localhost:29092`
- PostgreSQL: `localhost:5432`

Контейнер `postgres-main` запускается с `max_prepared_transactions=100`, что нужно для XA с PostgreSQL.

## Тестовые и демонстрационные сценарии

- Асинхронный screening: `POST apply` → polling `GET application`.
- Откат транзакции при invite: `POST /api/v1/admin/debug/schedule-failure/true`, затем `invite` — заявка/интервью/слот не должны остаться в несогласованном состоянии.
- Export to EIS: интервью в подходящем статусе, cron или `POST /api/v1/admin/jobs/export-interviews` — экспорт на **`external-eis`** через JCA (`APP_EIS_REMOTE_BASE_URL`).

## Важные ограничения реализации

- Распределённой транзакции между Kafka и PostgreSQL нет; события публикуются после commit (см. `AfterCommitEventPublisher`).
- WebSocket push только на процессах `api`.
- Идемпотентность обработки сообщений Kafka — таблица `processed_kafka_events`.
