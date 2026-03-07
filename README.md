# hh-process

Spring Boot + PostgreSQL проект для бизнес-процесса **обработки откликов на вакансию**.

## Что реализовано

- создание вакансий
- создание кандидатов
- подача отклика
- автоматическая валидация отклика
- решения HR: отказ, приглашение, резерв
- принятие приглашения кандидатом
- закрытие приглашения по таймауту
- история смены статусов
- лог уведомлений
- JWT authentication / authorization
- refresh token
- logout
- OpenAPI / Swagger UI
- Flyway миграции

## JWT auth

Открытые endpoint:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- Swagger/OpenAPI

Защищённые endpoint работают с заголовком:

```
Authorization: Bearer <access_token>
```

Роли:

- `USER` — кандидатские операции
- `HR` — HR-операции и просмотр служебных данных

Пример регистрации HR-пользователя:

```json
{
  "email": "hr@example.com",
  "password": "Admin123!",
  "role": "HR"
}
```

## Статусы отклика

- `NEW`
- `NEEDS_REVIEW`
- `UNDER_REVIEW`
- `AUTO_REJECTED`
- `HR_REJECTED`
- `INVITED`
- `IN_RESERVE`
- `ACCEPTED`
- `EXPIRED`

## Запуск

Нужны:

- Java 17+
- Maven 3.9+
- PostgreSQL 14+

Создай БД:

```sql
create database hh_process;
```

Можно использовать значения по умолчанию, либо задать переменные окружения:

- `POSTGRES_HOST`
- `POSTGRES_PORT`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASS`
- `JWT_SECRET`
- `JWT_ACCESS_EXPIRATION`
- `JWT_REFRESH_EXPIRATION`

Затем:

```bash
mvn clean package
java -jar target/hh-process-0.0.1-SNAPSHOT.jar
```

Swagger UI:

- `http://localhost:8080/swagger-ui.html`

## Основные endpoint

### Auth

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

### Business API

- `POST /api/v1/vacancies` (HR)
- `GET /api/v1/vacancies`
- `POST /api/v1/candidates`
- `GET /api/v1/candidates` (HR)
- `POST /api/v1/applications`
- `GET /api/v1/applications` (HR)
- `POST /api/v1/applications/{id}/validate` (HR)
- `POST /api/v1/applications/{id}/auto-reject` (HR)
- `POST /api/v1/applications/{id}/reject` (HR)
- `POST /api/v1/applications/{id}/invite` (HR)
- `POST /api/v1/applications/{id}/reserve` (HR)
- `POST /api/v1/applications/{id}/accept`
- `POST /api/v1/applications/{id}/expire` (HR)
- `GET /api/v1/applications/{id}/history` (HR)
