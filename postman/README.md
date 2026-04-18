# Postman

Актуальные артефакты для ЛР3:

- `HH Process API (ЛР2).postman_collection.json` — базовая коллекция REST API, которую нужно использовать как основу и дополнить сценариями ЛР3.
- `HH Process Local.postman_environment.json` — локальное окружение (`base_url`, Basic Auth учётные данные, runtime ids).

## Что поменялось в ЛР3

### 1. Подача отклика стала асинхронной

`POST /api/v1/candidates/vacancies/{vacancyId}` больше не возвращает финальное решение screening. Теперь ожидается ответ вида:

```json
{
  "application_id": "...",
  "status": "APPLICATION_SUBMITTED",
  "message": "Application submitted"
}
```

После этого в Postman нужно делать polling:

1. сохранить `application_id` в переменную окружения;
2. вызывать `GET /api/v1/candidates/applications/{{application_id}}`;
3. повторять, пока `status` не станет `ON_RECRUITER_REVIEW` или `SCREENING_FAILED` (пока идёт скрининг, для кандидата отображается `APPLICATION_SUBMITTED`).

### 2. Для distributed transaction появился debug trigger

Для демонстрации rollback можно вызвать:

`POST /api/v1/admin/debug/schedule-failure/true`

После этого `invite` должен упасть, а данные в main DB и schedule DB не должны зафиксироваться частично.

### 3. Для EIS export появился admin trigger

Для ручной постановки интервью на экспорт:

`POST /api/v1/admin/jobs/export-interviews`

## Рекомендуемые сценарии коллекции

### Async screening

1. Зарегистрировать кандидата.
2. Создать вакансию рекрутером.
3. Подать отклик кандидатом.
4. Polling статуса заявки до завершения screening.
5. Проверить уведомления рекрутера или кандидата.

### Distributed transaction

1. Создать вакансию.
2. Подать отклик и дождаться `ON_RECRUITER_REVIEW`.
3. Включить `schedule-failure`.
4. Попробовать `invite`.
5. Проверить, что интервью не появилось и статус заявки не стал `INVITED`.
6. Выключить `schedule-failure`.

### Export to EIS

1. Создать интервью.
2. Вызвать `POST /api/v1/admin/jobs/export-interviews`.
3. Проверить, что `app-eis-worker` обработал событие.
4. Проверить запись в `interview_export_log`.

## Аутентификация

Во всех запросах используется HTTP Basic. JWT в проекте не используется.

Базовые учётные данные:

- `admin@example.com / password123`
- `recruiter@example.com / password123`

Кандидата удобнее создавать через `POST /api/v1/auth/register/candidate` и затем записывать его e-mail/password в переменные окружения.
