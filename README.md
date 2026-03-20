# HH Process — сервис обработки откликов на вакансии

Spring Boot сервис для обработки жизненного цикла откликов на вакансии: регистрации кандидатов, создания/статусов вакансий рекрутёром, принятия решений по заявкам, а также уведомлений (REST + WebSocket).

## Конфигурация

Список используемых переменных окружения (берутся из `.env`):

```text
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_SCHEMA=public
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres

JWT_SECRET=... (base64-строка)
JWT_ACCESS_EXPIRATION (мс, по умолчанию 900000)
JWT_REFRESH_EXPIRATION (мс, по умолчанию 604800000)

WS_ALLOWED_ORIGINS=http://localhost:3000 (через запятую)
```

Сервер слушает порт `8080`.

## Авторизация (JWT)

Все защищенные endpoint’ы принимают заголовок:

`Authorization: Bearer <access_token>`

Роли определяют доступ к группам API:
- `/api/v1/candidates/**` -> `CANDIDATE`
- `/api/v1/recruiters/**` -> `RECRUITER`
- `/api/v1/admin/**` -> `ADMIN`

## REST API

Базовый префикс: `/api/v1`

### Auth

- `POST /api/v1/auth/register/candidate` (регистрация кандидата)
- `POST /api/v1/auth/login` (выдача access/refresh токенов)
- `POST /api/v1/auth/refresh` (обновление токена)
- `GET /api/v1/me` (профиль текущего пользователя)

Пример логина:

```bash
curl -sS -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"password123"}'
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
{ "response_type": "INVITATION_ACCEPTED|INVITATION_REJECTED", "message": "..." }
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
{ "status": "OPEN|CLOSED|..."}
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
