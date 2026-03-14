# HH Process — сервис обработки откликов

- JWT auth
- роли `CANDIDATE`, `RECRUITER`, `ADMIN`
- PostgreSQL + Flyway
- REST API
- WebSocket для push уведомлений
- в admin оставлена только ручная job закрытия просроченных приглашений
- срок жизни приглашения зашит в коде: **48 часов**

## Запуск

### 1. PostgreSQL
```bash
docker compose up -d postgres
```

### 2. Переменные окружения
```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=hh_process
export POSTGRES_USER=postgres
export POSTGRES_PASS=postgres
```

### 3. Приложение
```bash
mvn spring-boot:run
```

## Seed-пользователи
Создаются миграцией `V3__seed_users.sql`.

| Роль | Email | Пароль |
|---|---|---|
| ADMIN | admin@example.com | password123 |
| RECRUITER | recruiter@example.com | password123 |

Кандидат создается через `POST /api/v1/auth/register/candidate`.

## Основные endpoint'ы

### Auth
- `POST /api/v1/auth/register/candidate`
- `POST /api/v1/auth/login`
- `GET /api/v1/me` — текущий пользователь (по JWT)

### Candidate
- `POST /api/v1/candidates/vacancies/{vacancyId}` — отклик на вакансию (body: resumeText, coverLetter)
- `GET /api/v1/candidates/applications`
- `GET /api/v1/candidates/applications/{id}`
- `POST /api/v1/candidates/applications/{id}/invitation-response`
- `GET /api/v1/notifications`
- `PATCH /api/v1/notifications/{id}/read`

### Recruiter
- `POST /api/v1/recruiters/vacancies`
- `GET /api/v1/recruiters/vacancies`
- `PATCH /api/v1/recruiters/vacancies/{id}/status`
- `GET /api/v1/recruiters/applications`
- `GET /api/v1/recruiters/applications/{id}`
- `POST /api/v1/recruiters/applications/{id}/reject`
- `POST /api/v1/recruiters/applications/{id}/invite`

### Admin
- `POST /api/v1/admin/jobs/close-expired-invitations`

## WebSocket
- endpoint: `/ws`
- user notifications: `/user/queue/notifications`
- JWT можно передать в STOMP header `Authorization: Bearer <token>`

## Swagger
- `http://localhost:8080/swagger-ui.html`

## Curl-сценарий
```bash
bash scripts/test-api.sh
```
