# HH Process — сервис обработки откликов на вакансии

- **JWT auth**: access- и refresh-токены (оба в формате JWT)
- Роли: `CANDIDATE`, `RECRUITER`, `ADMIN`
- PostgreSQL + Flyway, схема задаётся через `POSTGRES_SCHEMA`
- REST API, ответы в **snake_case**
- WebSocket (STOMP) для push-уведомлений
- Автоскрининг резюме по списку навыков вакансии
- Срок приглашения: **48 часов**; просроченные закрываются джобой или по расписанию

## Требования

- Java 17+
- Maven 3.x
- Docker и Docker Compose (для БД или полного стека)

## Запуск

### Вариант 1: Docker Compose (БД + приложение)

Создайте файл `.env` в корне проекта (можно скопировать с `.env.example`):

```bash
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=postgres
POSTGRES_SCHEMA=public
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
JWT_SECRET=<base64-не-короче-32-байт-для-HS256>
```

Опционально: `JWT_ACCESS_EXPIRATION` (мс, по умолчанию 900000), `JWT_REFRESH_EXPIRATION` (мс, по умолчанию 604800000), `WS_ALLOWED_ORIGINS`.

Запуск:

```bash
docker compose up --build
```

Приложение: `http://localhost:8080`, БД: `localhost:5432`.

### Вариант 2: Локально только БД, приложение — через Maven

```bash
docker compose up -d postgres
```

Переменные окружения для приложения:

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=postgres
export POSTGRES_SCHEMA=public
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=postgres
export JWT_SECRET="<base64-секрет-не-короче-32-байт>"
```

Запуск приложения:

```bash
mvn spring-boot:run
```

## Seed-пользователи

Создаются миграцией `V3__seed.sql`.

| Роль      | Email                | Пароль     |
|-----------|----------------------|------------|
| ADMIN     | admin@example.com    | password123 |
| RECRUITER | recruiter@example.com | password123 |

Кандидаты создаются через `POST /api/v1/auth/register/candidate`.

## API

### Auth

- `POST /api/v1/auth/register/candidate` — регистрация кандидата (body: `email`, `password`, `first_name`, `last_name`)
- `POST /api/v1/auth/login` — вход (body: `email`, `password`). Ответ: `access_token`, `refresh_token`, `expires_in` (секунды жизни access-токена)
- `POST /api/v1/auth/refresh` — обновление пары токенов (body: `refresh_token`). Ответ: `access_token`, `refresh_token`, `expires_in`
- `GET /api/v1/me` — текущий пользователь (заголовок `Authorization: Bearer <access_token>`). Ответ: `user_id`, `email`, `roles` (массив)

### Кандидат

- `POST /api/v1/candidates/vacancies/{vacancyId}` — отклик (body: `resume_text`, `cover_letter`)
- `GET /api/v1/candidates/applications` — список своих откликов
- `GET /api/v1/candidates/applications/{id}` — отклик по id
- `POST /api/v1/candidates/applications/{id}/invitation-response` — ответ на приглашение (body: `response_type`, `message`)

### Рекрутер

- `POST /api/v1/recruiters/vacancies` — создание вакансии (body: `title`, `description`, `required_skills`, `screening_threshold`)
- `GET /api/v1/recruiters/vacancies` — свои вакансии
- `PATCH /api/v1/recruiters/vacancies/{id}/status` — смена статуса (body: `status`, например `CLOSED`)
- `GET /api/v1/recruiters/applications` — список откликов (query: `status`, `vacancy_id`)
- `GET /api/v1/recruiters/applications/{id}` — отклик по id
- `POST /api/v1/recruiters/applications/{id}/reject` — отказ (body: `comment`)
- `POST /api/v1/recruiters/applications/{id}/invite` — приглашение (body: `message`)

### Уведомления (любая авторизованная роль)

- `GET /api/v1/notifications` — список уведомлений
- `PATCH /api/v1/notifications/{id}/read` — отметить прочитанным

### Admin

- `POST /api/v1/admin/jobs/close-expired-invitations` — вручную закрыть просроченные приглашения (ответ: `closed_count`)

## WebSocket

- Endpoint: `/ws` (SockJS + STOMP)
- Подписка на уведомления: `/user/queue/notifications`
- При подключении передать JWT в заголовке: `Authorization: Bearer <access_token>`

## Дополнительно

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Actuator**: `GET /actuator/health`, `GET /actuator/info`
- **Postman**: коллекция и окружение в папке `postman/`

## Проверка API

```bash
bash scripts/test-api.sh
```

(при наличии скрипта и настроенных переменных окружения)
