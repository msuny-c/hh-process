# Postman (ЛР2)

Файлы:

- `HH Process API (ЛР2).postman_collection.json` — коллекция REST API под текущий сервис (HTTP Basic, не JWT).
- `HH Process Local.postman_environment.json` — переменные окружения (`base_url`, учётные данные из `users.xml`).

## Импорт

1. Импортируйте коллекцию и environment в Postman.
2. Выберите окружение **HH Process Local**.
3. Убедитесь, что `admin_email` / `recruiter_email` и пароли совпадают с `APP_SECURITY_USERS_XML` (и что кандидаты создаются через **Регистрация кандидата** в коллекции). После успешной регистрации в окружение пишутся `candidate_email`, `candidate_password`, `candidate_first_name`, `candidate_last_name`, `candidate_user_id`, `candidate_role`.

## Порядок запуска

Сверху вниз по папкам:

1. **01** — health, регистрация кандидата, профили по ролям (Basic).
2. **02** — вакансии рекрутера.
3. **03** — happy path: отклик → приглашение → ответ кандидата (сохраняется `interview_id`).
4. **04–07** — негативные и уведомления по сценариям.
5. **08** — админ: джоба закрытия просроченных приглашений (`JOB_RUN_TIMEOUT_CLOSE`).
6. **09 ЛР2** — явное закрытие вакансии `POST .../close` (JTA), расписание, отмена интервью, негатив 403 (рекрутер → admin job).

Папку **09** удобно запускать после **03**, чтобы были заполнены `interview_id` и авторизация рекрутера. Отмена интервью имеет смысл **до** ответа кандидата на приглашение; иначе возможен конфликт состояния (в тесте допускаются коды 200 и 409).

## Аутентификация

У запросов настроен **HTTP Basic** с переменными `{{recruiter_email}}` / `{{candidate_email}}` / `{{admin_email}}`. Токены JWT не используются.

## Формат JSON

Тела запросов и ожидаемые поля ответов в коллекции заданы в **snake_case** (`first_name`, `required_skills`, `application_id` и т.д.), в соответствии с `spring.jackson.property-naming-strategy: SNAKE_CASE` в приложении. Исключение: query-параметр расписания — `weekOffset` (имя параметра метода в Spring).
