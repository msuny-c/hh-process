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
- OpenAPI / Swagger UI
- Flyway миграции

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

## Допустимые переходы

- `NEW -> NEEDS_REVIEW`
- `NEW -> UNDER_REVIEW`
- `NEEDS_REVIEW -> UNDER_REVIEW`
- `UNDER_REVIEW -> AUTO_REJECTED`
- `UNDER_REVIEW -> HR_REJECTED`
- `UNDER_REVIEW -> INVITED`
- `UNDER_REVIEW -> IN_RESERVE`
- `NEEDS_REVIEW -> HR_REJECTED`
- `NEEDS_REVIEW -> INVITED`
- `NEEDS_REVIEW -> IN_RESERVE`
- `INVITED -> ACCEPTED`
- `INVITED -> EXPIRED`

## Запуск

Нужны:

- Java 17+
- Maven 3.9+
- PostgreSQL 14+

Создай БД:

```sql
create database hh_process;
```

Проверь настройки в `src/main/resources/application.yml`, затем:

```bash
mvn clean package
java -jar target/hh-process-0.0.1-SNAPSHOT.jar
```

Swagger UI:

- `http://localhost:8080/swagger-ui.html`

## Основные endpoint

- `POST /api/v1/vacancies`
- `GET /api/v1/vacancies`
- `POST /api/v1/candidates`
- `GET /api/v1/candidates`
- `POST /api/v1/applications`
- `GET /api/v1/applications`
- `POST /api/v1/applications/{id}/validate`
- `POST /api/v1/applications/{id}/auto-reject`
- `POST /api/v1/applications/{id}/reject`
- `POST /api/v1/applications/{id}/invite`
- `POST /api/v1/applications/{id}/reserve`
- `POST /api/v1/applications/{id}/accept`
- `POST /api/v1/applications/{id}/expire`
- `GET /api/v1/applications/{id}/history`

## Соответствие BPMN

Участники процесса:

- кандидат
- система
- HR-менеджер

Система сохраняет отклик, выполняет первичную проверку и переводит отклик либо на ручную обработку, либо на HR-review. Затем HR принимает решение: отказ, приглашение или резерв. После приглашения кандидат может принять его, либо приглашение истекает по таймауту.
