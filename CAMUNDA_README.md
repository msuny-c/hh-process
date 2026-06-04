# Camunda / BPMN: запуск процессов, роли и просмотр выполнения

Этот файл описывает, как запускать Camunda-процессы в лабораторной работе, какие роли используются, как зайти под пользователями и как смотреть выполнение процесса на BPMN-диаграмме.

## 1. Что запускается

Проект состоит из трёх основных сервисов из `docker-compose.yml`:

| Сервис | Назначение | URL локально |
|---|---|---|
| `postgres` | БД приложения и БД Camunda | `localhost:5432` |
| `camunda` | standalone Camunda BPM Platform Run | `http://localhost:8081` |
| `app` | приложение в WildFly, REST API, worker Camunda external tasks | `http://localhost:8080` |

Основные ссылки после запуска:

| Что открыть | URL |
|---|---|
| Приложение / REST API | `http://localhost:8080/api/v1/...` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Camunda Web Apps | `http://localhost:8081/camunda/app/` |
| Camunda Tasklist | `http://localhost:8081/camunda/app/tasklist/default/` |
| Camunda Cockpit | `http://localhost:8081/camunda/app/cockpit/default/` |
| Camunda Admin | `http://localhost:8081/camunda/app/admin/default/` |

## 2. Как запустить проект локально

Из корня проекта:

```bash
docker compose down -v
docker compose up --build
```

После старта нужно дождаться, пока:

1. PostgreSQL станет healthy.
2. Camunda поднимется на `8081`.
3. WildFly-приложение поднимется на `8080`.
4. Приложение задеплоит BPMN и формы в Camunda.
5. Запустится Camunda external task worker.

Проверка:

```bash
curl http://localhost:8080/actuator/health
```

Если проект запускается не через Docker, важно включить PostgreSQL prepared transactions:

```conf
max_prepared_transactions = 100
```

Это нужно для JTA/Narayana.

## 3. Учетные записи и роли

В приложении используются роли:

| Роль | Что делает |
|---|---|
| `CANDIDATE` | создаёт отклик, отвечает на приглашение |
| `RECRUITER` | создаёт вакансию, рассматривает отклики, приглашает/отклоняет кандидатов, закрывает вакансию |
| `ADMIN` | выполняет административный сброс интервью |
| `SYSTEM` / worker | выполняет service task, транзакции, уведомления, таймеры |

Seed-пользователи приложения:

| Пользователь приложения | Роль | Пароль в приложении |
|---|---|---|
| `admin@example.com` | `ADMIN` | `password123` |
| `recruiter@example.com` | `RECRUITER` | `password123` |

Кандидата можно создать через REST:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register/candidate \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "candidate@example.com",
    "password": "password123",
    "full_name": "Candidate User"
  }'
```

## 4. Как войти в Camunda

Администратор Camunda из `docker-compose.yml`:

| Логин | Пароль |
|---|---|
| `admin` | `admin` |

Синхронизированные пользователи приложения также создаются в Camunda Identity:

| Пользователь Camunda | Группа Camunda | Пароль Camunda |
|---|---|---|
| `adminexamplecom` | `ADMIN` | значение `CAMUNDA_IDENTITY_SYNC_INITIAL_PASSWORD`, по умолчанию `camunda` |
| `recruiterexamplecom` | `RECRUITER` | значение `CAMUNDA_IDENTITY_SYNC_INITIAL_PASSWORD`, по умолчанию `camunda` |
| новый кандидат | `CANDIDATE` | пароль, который он ввёл при регистрации |

То есть для проверки Tasklist можно зайти так:

| Роль | Логин в Camunda | Пароль |
|---|---|---|
| Camunda admin | `admin` | `admin` |
| Рекрутер | `recruiterexamplecom` | `camunda` |
| Админ приложения | `adminexamplecom` | `camunda` |
| Новый кандидат | email без символов кроме букв и цифр | пароль кандидата при регистрации |

> Важно: Camunda 7.23 принимает в `user.id` только ограниченный набор символов, поэтому приложение синхронизирует пользователей с безопасным буквенно-цифровым id (`admin@example.com` → `adminexamplecom`), но исходный email остаётся в профиле Camunda. Пароль в приложении и пароль синхронизированного seed-пользователя в Camunda могут отличаться. Для seed-пользователей Camunda использует fallback из `CAMUNDA_IDENTITY_SYNC_INITIAL_PASSWORD`. Для новых кандидатов пароль в Camunda задаётся при регистрации.

## 5. Какие BPMN-процессы есть

Файлы лежат в `src/main/resources/camunda`.

| BPMN | Process key | Исполняемый | Назначение |
|---|---|---:|---|
| `hh-vacancy-process.bpmn` | `hhVacancyProcess` | да | создание и закрытие вакансии рекрутером |
| `hh-application-process.bpmn` | `hhApplicationProcess` | да | отклик кандидата, автоскрининг, решение рекрутера, приглашение, ответ кандидата |
| `hh-timeout-scheduler.bpmn` | `hhTimeoutSchedulerProcess` | да | периодическое закрытие просроченных приглашений |
| `hh-admin-interview-reset.bpmn` | `hhAdminInterviewResetProcess` | да | административный сброс интервью |
| `hh-vacancy-status-update.bpmn` | `hhVacancyStatusUpdateProcess` | да | изменение статуса вакансии через BPMN |
| `hh-recruiter-interview-cancel.bpmn` | `hhRecruiterInterviewCancelProcess` | да | отмена интервью рекрутером |
| `hh-ui-*.bpmn` | `hhUi...` | да | Camunda Forms экраны списков/просмотров вакансий, откликов, расписания, уведомлений и ручного timeout review |
| `hh-process-overview.bpmn` | `hhProcessOverview` | нет | обзорная схема с ролями, только для отчёта/защиты |

## 6. Как запускать процессы через Camunda Tasklist

Открой Tasklist:

```text
http://localhost:8081/camunda/app/tasklist/default/
```

Дальше:

```text
Start process → выбрать process definition → Start
```

### 6.1. Создание вакансии рекрутером через Camunda

Войти в Camunda как рекрутер:

```text
login: recruiterexamplecom
password: camunda
```

Запустить процесс:

```text
Start process → hhVacancyProcess
```

Дальше появится user task:

```text
Заполнить форму вакансии
```

Форма: `create-vacancy-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `title` | название вакансии |
| `description` | описание вакансии |
| `requiredSkills` | навыки через запятую, например `Java, Spring, PostgreSQL` |
| `screeningThreshold` | порог автоскрининга от `0` до `100` |

После submit:

1. `ValidateCreateVacancyForm` проверяет данные формы.
2. `CreateVacancyFromForm` создаёт вакансию в БД.
3. Процесс получает `vacancyId` и business key `vacancy:<id>`.
4. Показывается result form `Вакансия создана`.
5. Потом появляется задача `Управлять вакансией`.

Если форма заполнена некорректно, BPMN error `FORM_VALIDATION_FAILED` возвращает процесс обратно к форме.

### 6.2. Закрытие вакансии рекрутером

Войти как рекрутер и открыть свою задачу:

```text
Управлять вакансией
```

Форма: `close-vacancy-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `action` | действие, например закрыть вакансию |
| `closeReason` | причина закрытия |

После закрытия:

1. `ValidateCloseVacancyForm` проверяет форму.
2. `Transaction_CloseVacancy` закрывает вакансию, интервью, слоты и активные отклики.
3. `NotifyVacancyClosedCandidates` уведомляет кандидатов.
4. `CorrelateVacancyClosedApplications` отправляет `MSG_VACANCY_CLOSED` в активные процессы откликов.
5. В связанных `hhApplicationProcess` срабатывает boundary message event.

### 6.3. Отклик кандидата

Есть два варианта.

Основной API-вариант:

```text
POST /api/v1/candidates/vacancies/{vacancyId}
```

После этого backend создаёт отклик и стартует `hhApplicationProcess`.

Camunda-вариант возможен, если процесс стартует с переменными, которые связывают отклик с конкретной вакансией/кандидатом. Минимально нужен `vacancyId`; кандидатская роль берётся из Camunda user/group. После старта появляется user task:

```text
Заполнить форму отклика
```

Форма: `apply-to-vacancy-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `resumeText` | текст резюме, минимум 20 символов |
| `coverLetter` | сопроводительное письмо, необязательно |

После submit:

1. `ValidateApplyToVacancyForm` проверяет форму.
2. `AutoScreenApplication` выполняет автоскрининг.
3. Если скрининг не пройден, кандидат получает result form.
4. Если скрининг пройден, рекрутер получает задачу `Рассмотреть отклик рекрутером`.

### 6.4. Рассмотрение отклика рекрутером

Войти как рекрутер:

```text
recruiterexamplecom / camunda
```

Открыть задачу:

```text
Рассмотреть отклик рекрутером
```

Форма: `recruiter-decision-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `decision` | решение: пригласить или отказать |
| `recruiterComment` | комментарий |

Если рекрутер отказывает:

```text
ValidateRejectionAllowed
→ Transaction_Rejection
→ NotifyRejection
→ result form для кандидата
```

Если рекрутер приглашает:

```text
WriteInvitationTask
```

### 6.5. Подготовка приглашения

Задача рекрутера:

```text
Подготовить приглашение на интервью
```

Форма: `invitation-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `invitationMessage` | текст приглашения |
| `scheduledAt` | дата и время интервью в ISO-8601, например `2026-06-03T15:00:00` |
| `durationMinutes` | длительность от 15 до 480 минут |

После submit:

```text
ValidateInvitationForm
→ Transaction_Invitation
   → Сохранить текст приглашения
   → Создать интервью
   → Зарезервировать слот рекрутера
   → Записать историю приглашения
→ NotifyInvitation
→ CandidateInvitationResponseTask
```

Если данные формы некорректны, процесс возвращается на `WriteInvitationTask` и показывает `formErrorMessage`.

### 6.6. Ответ кандидата на приглашение

Войти как кандидат:

```text
candidateexamplecom / пароль кандидата
```

Открыть задачу:

```text
Ответить на приглашение
```

Форма: `invitation-response-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `responseType` | принять или отклонить приглашение |
| `responseMessage` | комментарий кандидата |

После submit:

```text
ValidateCandidateResponseForm
→ Transaction_Response
   → Проверить активность приглашения
   → Сохранить ответ кандидата
   → Обновить статус отклика
   → Записать историю ответа
→ NotifyCandidateResponse
→ result form
```

### 6.7. Административный сброс интервью

Войти как админ:

```text
adminexamplecom / camunda
```

Запустить процесс:

```text
Start process → hhAdminInterviewResetProcess
```

Открыть задачу:

```text
Подтвердить сброс интервью
```

Форма: `reset-interview-form`.

Поля:

| Поле | Что вводить |
|---|---|
| `interviewId` | id интервью |
| `resetReason` | причина сброса |

После submit:

```text
ValidateAdminResetForm
→ Transaction_AdminReset
   → Проверить возможность сброса интервью
   → Отменить интервью
   → Освободить слот интервью
   → Вернуть отклик на рассмотрение
   → Записать историю сброса
→ NotifyAdminResetParticipants
→ result form
```

Ошибки формы возвращают процесс на форму. Ошибки транзакции идут в `RollbackAdminReset`.

### 6.8. Timeout scheduler

Процесс:

```text
hhTimeoutSchedulerProcess
```

Он запускается по timer start event и периодически закрывает просроченные приглашения.

Логика:

```text
Найти одно просроченное приглашение
→ если найдено:
   → отменить интервью
   → освободить слот
   → закрыть отклик
   → записать историю
   → уведомить участников
   → завершить процесс отклика
   → вернуться к поиску следующего
→ если не найдено:
   → завершить batch
```

## 7. Как смотреть выполнение на диаграмме

Открой Cockpit:

```text
http://localhost:8081/camunda/app/cockpit/default/
```

Дальше:

```text
Processes → выбрать процесс → Running Instances / Instances
```

Или:

```text
Cockpit → Process Definitions → hhVacancyProcess / hhApplicationProcess / ...
```

В Cockpit можно смотреть:

| Что смотреть | Где |
|---|---|
| текущий активный шаг | Process instance diagram, подсвеченный элемент |
| переменные процесса | Variables |
| business key | Instance details |
| ошибки external task | Incidents |
| историю выполнения | History / completed activity instances |
| ожидающие user tasks | Tasklist или Cockpit runtime state |

Примеры business key:

| Процесс | Business key |
|---|---|
| вакансия | `vacancy:<vacancyId>` |
| отклик | `application:<applicationId>` |

## 8. Как понять, что роли работают

В BPMN user tasks размечены через `candidateGroups`:

| Задача | Группа |
|---|---|
| `CreateVacancyTask` | `RECRUITER` |
| `ManageVacancyTask` | `RECRUITER` |
| `RecruiterDecisionTask` | `RECRUITER` |
| `WriteInvitationTask` | `RECRUITER` |
| `ApplyToVacancyTask` | `CANDIDATE` |
| `CandidateInvitationResponseTask` | `CANDIDATE` |
| `AdminResetApprovalTask` | `ADMIN` |

При старте приложения `CamundaIdentitySyncService` создаёт в Camunda:

```text
users
roles as groups
user-group memberships
```

Проверка:

```text
Camunda Admin → Users
Camunda Admin → Groups
Camunda Admin → Authorizations
```

Также доступ защищён на backend-уровне через Spring Security и ownership checks: рекрутер не должен завершать задачи по чужой вакансии, кандидат — по чужому отклику.

## 9. Как смотреть ошибки и recovery

В BPMN используется простая и понятная модель:

```text
Transaction SubProcess
+ Error Boundary Event
+ Rollback / Recovery Service Task
```

Настоящие BPMN compensation/cancel boundary events intentionally не используются, чтобы не перегружать схему. Технический rollback БД выполняется через:

```text
@Transactional
Narayana / JTA
PostgreSQL prepared transactions
```

На BPMN уровне ошибки формы идут через:

```text
FORM_VALIDATION_FAILED
→ сохранить сообщение ошибки
→ вернуть пользователя на форму
```

Ошибки бизнес-транзакций идут через rollback/recovery service task:

```text
RollbackApplicationTransaction
RollbackVacancyTransaction
RollbackAdminReset
```

## 10. Быстрый демонстрационный сценарий для защиты

Рекомендуемый сценарий:

1. Запустить проект через `docker compose up --build`.
2. Зайти в Camunda Tasklist как `recruiterexamplecom / camunda`.
3. Стартовать `hhVacancyProcess`.
4. Заполнить `create-vacancy-form`.
5. Открыть Cockpit и показать, что процесс дошёл до `ManageVacancyTask`.
6. Создать кандидата через REST `/api/v1/auth/register/candidate`.
7. Создать отклик через API или Camunda-сценарий.
8. Зайти как рекрутер и выполнить `RecruiterDecisionTask`.
9. Подготовить приглашение через `invitation-form`.
10. Зайти как кандидат и выполнить `CandidateInvitationResponseTask`.
11. В Cockpit показать completed activities и variables.
12. Отдельно показать `hhTimeoutSchedulerProcess` и `hhAdminInterviewResetProcess` как процессы для периодической задачи и административной операции.
13. Закрыть вакансию и показать `MSG_VACANCY_CLOSED` / переход активных application process в ветку закрытия вакансии.

Сценарий без Swagger/Postman, только через Camunda Tasklist и Forms: `docs/CAMUNDA_TASKLIST_DEMO.md`.

## 11. Соответствие требованиям лабораторной

| Требование | Где реализовано |
|---|---|
| Camunda как BPM-движок | standalone container `camunda` в `docker-compose.yml` |
| BPMN 2.0 | `src/main/resources/camunda/*.bpmn` |
| Camunda Forms | `src/main/resources/camunda/forms/*.form` |
| UI через Camunda forms | Tasklist user tasks: vacancy, application, invitation, response, admin reset, result forms, `hh-ui-*.bpmn` query screens |
| Роли | `candidateStarterGroups` / `candidateGroups` в BPMN + `CamundaIdentitySyncService` + Spring Security |
| Транзакции | `bpmn:transaction` + `@Transactional` + Narayana/JTA |
| Асинхронная обработка | Camunda external tasks и worker |
| Периодические задачи | `hh-timeout-scheduler.bpmn` с timer start event |
| Межпроцессное сообщение | `MSG_VACANCY_CLOSED` между vacancy process и application process |
| Backend API адаптирован под Camunda | REST facade стартует/двигает процессы; создание вакансии, создание отклика, изменение статуса и отмена интервью выполняются worker/delegate-логикой из BPMN |
| WildFly | сервис `app` в Dockerfile/docker-compose, сборка WAR через Maven |

## 12. Что важно проверить перед сдачей

Минимальный checklist:

```text
mvn clean package
```

Потом:

```text
docker compose down -v
docker compose up --build
```

Проверить:

- Camunda открывается на `8081`.
- WildFly-приложение открывается на `8080`.
- BPMN-процессы видны в Cockpit.
- Forms видны в Tasklist.
- `recruiterexamplecom` видит recruiter tasks.
- `adminexamplecom` видит admin reset task.
- кандидат после регистрации видит candidate tasks.
- создание вакансии через Camunda реально создаёт запись в БД.
- ошибки формы возвращают пользователя обратно на форму.
- закрытие вакансии отправляет `MSG_VACANCY_CLOSED` активным откликам.
- timer process закрывает просроченные приглашения.
