# Postman

В архиве:
- `HH Process API - Basic Auth + Composite Transactions.postman_collection.json`
- `HH Process Local.postman_environment.json`

Как использовать:
1. Импортируй оба файла в Postman.
2. Выбери environment `HH Process Local`.
3. Проверь логины/пароли под свою XML-конфигурацию.
4. Запускай папки сверху вниз:
   - `00 Auth & Context`
   - `01 Recruiter Vacancies`
   - `02 Candidate Applications`
   - `03 Recruiter Applications & Interviews`
   - `04 Schedule`
   - `05 Notifications`
   - `06 Admin Jobs`
   - `07 Negative & Validation`

Что автоматизируется:
- регистрация уникального кандидата
- переключение между admin / recruiter / candidate
- сохранение `vacancyId`, `applicationId`, `interviewId`, `notificationId`

Замечания:
- запросы используют HTTP Basic на уровне коллекции
- регистрация кандидата выполняется без аутентификации
- негативные сценарии рассчитаны на запуск после happy path
