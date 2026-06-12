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
| Приложение / health check | `http://localhost:8080/actuator/health` |
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
max_prepared_transactions = 200
max_connections = 200
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
    "first_name": "Candidate",
    "last_name": "User"
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

### 4.1. Identity provider и task listeners

Так как Camunda запущена как standalone-сервис, Java-классы приложения не встраиваются внутрь движка как `delegateExpression`/`class` listeners. Вместо этого приложение работает как внешний identity provider и task-listener adapter через Camunda REST API:

1. `CamundaIdentityProviderService` на старте выполняет единый identity provisioning: создаёт группы `CANDIDATE`, `RECRUITER`, `ADMIN`, выдаёт start authorizations, синхронизирует users/memberships и пересоздаёт Tasklist filters.[^identity-provider]
2. `CamundaTaskListenerAdapter` периодически читает активные user tasks, сортирует их по свежести, определяет владельца по `candidateUserId`, `recruiterUserId`, `adminUserId` или `starterUserId`, назначает `assignee` и выдаёт task-level authorization.[^task-listener]
3. Если роль пользователя не совпадает с группой задачи, adapter не назначает task. Это дополняет BPMN `candidateGroups` и DMN `hhOperationPermissions`.

На защите это видно в Tasklist: задачи рекрутера назначаются `recruiterexamplecom`, задачи кандидата — синхронизированному кандидату, админские задачи — `adminexamplecom`.

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
| `hh-notification-process.bpmn` | `hhNotificationProcess` | да | reusable subprocess отправки уведомлений через call activity |
| `hh-ui-*.bpmn` | `hhUi...` | да | Camunda Forms экраны списков/просмотров вакансий, откликов, расписания, уведомлений и ручного timeout review |
| `hh-process-overview.bpmn` | `hhProcessOverview` | нет | обзорная схема с ролями, только для отчёта/защиты |

DMN-таблицы деплоятся вместе с BPMN:

| DMN | Decision key | Что решает |
|---|---|---|
| `hh-operation-permissions.dmn` | `hhOperationPermissions` | `role + operation + ownership -> allowed` перед ключевыми user tasks |
| `hh-auto-screening.dmn` | `hhAutoScreening` | `screeningScore - screeningThreshold -> screeningPassed` |
| `hh-status-transitions.dmn` | `hhStatusTransitions` | `currentStatus + action + requestedStatus -> allowed/nextStatus` |
| `hh-notification-templates.dmn` | `hhNotificationTemplates` | `notificationKind + status + recipientRole -> type/template` |

### 5.1. Если Camunda Modeler пишет `no diagram to display`

Такой файл может быть валидным для Camunda engine, но невалидным для визуального редактора. Причина: в BPMN XML есть процесс (`bpmn:process`), но нет визуального слоя `bpmndi:BPMNDiagram` / `bpmndi:BPMNPlane` / `bpmndi:BPMNShape` / `bpmndi:BPMNEdge`.

В этом проекте все BPMN-файлы из `src/main/resources/camunda` приведены к визуальному контракту:

- есть `bpmn:collaboration` и `bpmn:participant`, то есть pool;
- есть минимум один `bpmn:lane` с читаемым названием;
- все `startEvent` и `endEvent`, включая события внутри transaction subprocess, подписаны;
- `bpmndi:BPMNPlane` указывает на collaboration/pool;
- у flow nodes есть `bpmndi:BPMNShape`, а у `sequenceFlow` есть `bpmndi:BPMNEdge`.

Поэтому Camunda Modeler должен открывать:

- все основные процессы;
- все `hh-ui-*.bpmn` процессы;
- `hh-recruiter-interview-cancel.bpmn`;
- `hh-vacancy-status-update.bpmn`.

Проверка структуры:

```bash
python3 test/test_camunda_visual_model_contract.py
python3 test/test_camunda_model_coverage.py
```

Если оба теста проходят, файлы не являются “голым XML”: у них есть pool/lane/DI-слой, и Modeler открывает их как диаграммы.[^bpmn-visual-test]

### 5.2. Откуда в `hh-vacancy-process.bpmn` взялись `VF2c`, `VF2d` и похожие стрелки

`VF2c`, `VF2d`, `VF2e` и т.д. — это не отдельная магия Camunda, а обычные `id` у `bpmn:sequenceFlow` внутри vacancy process. Префикс `VF` читается как `Vacancy Flow`: он нужен только для стабильных идентификаторов стрелок.

Самые важные:

| Flow id | Что соединяет | Зачем нужен |
|---|---|---|
| `VF2a` | `VacancyCreationModeGateway -> CreateVacancyTask` | обычный старт через Tasklist form |
| `VF2b` | `VacancyCreationModeGateway -> ValidateCreateVacancyForm` | REST-режим с `restAutoSubmit=true` без ручной формы |
| `VF2c` | `CreateVacancyFormError -> CreateVacancyTask` | возврат на форму, если validation service task бросил `FORM_VALIDATION_FAILED` |
| `VF2e` | `CreateVacancyPersistError -> CreateVacancyTask` | возврат на форму, если ошибка возникла при создании вакансии |
| `VF3e` | `CloseVacancyFormError -> ManageVacancyTask` | возврат на форму закрытия вакансии после некорректных данных |
| `VF4` | `CloseVacancyTransitionGateway -> Transaction_CloseVacancy` | вход в BPMN transaction subprocess закрытия вакансии после DMN-разрешения |
| `VF7` | `CloseActiveApplicationsErrorBoundary -> RollbackVacancyTransaction` | переход в recovery task при BPMN error |

Раньше в схеме оставался лишний поток `CreateVacancyTask -> ManageVacancyTask`, который визуально выглядел как непонятный обход создания вакансии. Сейчас он удалён: после ручной формы процесс всегда идёт в `ValidateCreateVacancyForm`, затем в `CreateVacancyFromForm`, result task и только потом в `ManageVacancyTask`.

### 5.3. Единая валидация форм и повторное использование формы

Все пользовательские формы имеют два слоя проверки:

1. Camunda Form JSON содержит client-side `validate`: required, min/max length, min/max number.
2. Java adapter выполняет server-side проверку через `CamundaFormValidator`; `CamundaExternalTaskWorker` превращает ошибку в BPMN error `FORM_VALIDATION_FAILED`.

В BPMN после такой ошибки есть явная петля обратно на исходную user task. Например:

| BPMN | Validation/service task | Возврат на форму |
|---|---|---|
| `hh-application-process.bpmn` | `ValidateApplyToVacancyForm` | `ApplyToVacancyTask` |
| `hh-application-process.bpmn` | `ValidateRecruiterDecisionForm` | `RecruiterDecisionTask` |
| `hh-application-process.bpmn` | `ValidateInvitationForm` | `WriteInvitationTask` |
| `hh-vacancy-process.bpmn` | `ValidateCreateVacancyForm` | `CreateVacancyTask` |
| `hh-vacancy-process.bpmn` | `ValidateCloseVacancyForm` | `ManageVacancyTask` |
| `hh-ui-candidate-application-view.bpmn` | `LoadCandidateApplicationView` | `EnterCandidateApplicationId` |
| `hh-ui-recruiter-schedule.bpmn` | `LoadRecruiterSchedule` | `EnterScheduleWeek` |

Проверка контракта:

```bash
python3 test/test_camunda_model_coverage.py
```

Тест проверяет, что каждый перечисленный `FORM_VALIDATION_FAILED` действительно возвращает процесс к форме, а не создаёт incident и не завершает user path.

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

Если форма заполнена некорректно, BPMN error `FORM_VALIDATION_FAILED` возвращает процесс обратно к форме. В переменных остаются `formErrorMessage`, `formErrorField`, `formErrorFields`, `formErrorCode`, поэтому пользователь видит причину и поле, которое нужно исправить.

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

Если данные формы некорректны, процесс возвращается на `WriteInvitationTask` и показывает `formErrorMessage` вместе с `formErrorFields`.

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

В BPMN используется модель:

```text
Transaction SubProcess
+ Error Boundary Event
+ Rollback / Recovery Service Task
+ Cancel Boundary Event
+ Compensation Boundary Event
+ Compensation Handler
```

Cancel/compensation элементы добавлены как видимые BPMN-механизмы вокруг основных `bpmn:transaction`: закрытие вакансии, отказ/приглашение/ответ кандидата, admin reset, recruiter cancel и timeout batch. Технический rollback БД выполняется через:

```text
@Transactional
Narayana / JTA
PostgreSQL prepared transactions
```

На BPMN уровне ошибки формы идут через:

```text
FORM_VALIDATION_FAILED
→ сохранить formErrorMessage/formErrorField/formErrorFields/formErrorCode
→ вернуть пользователя на форму
```

Ошибки бизнес-транзакций идут через rollback/recovery service task:

```text
RollbackApplicationTransaction
RollbackVacancyTransaction
RollbackAdminReset
```

### 9.1. Визуальная состоятельность BPMN

Все BPMN-файлы в `src/main/resources/camunda` должны оставаться не только исполняемыми, но и открываемыми в Camunda Modeler:

- у каждого процесса есть pool и lane;
- start/end events подписаны;
- каждый flow node имеет `BPMNShape`, каждый `sequenceFlow` имеет `BPMNEdge`;
- стрелки касаются source/target shape;
- transaction/subProcess нарисованы раскрытыми;
- call activity и business rule task используют `latest` binding;
- крупные пустые горизонтальные разрывы в DI-координатах сжаты, чтобы блоки не уезжали слишком далеко друг от друга.

Проверка:

```bash
python3 test/test_camunda_visual_model_contract.py
python3 test/test_camunda_model_coverage.py
```

## 10. Быстрый демонстрационный сценарий для защиты

Рекомендуемый сценарий:

1. Запустить проект через `docker compose up --build`.
2. Зайти в Camunda Tasklist как `recruiterexamplecom / camunda`.
3. Стартовать `hhVacancyProcess`.
4. Заполнить `create-vacancy-form`.
5. Открыть Cockpit и показать, что процесс дошёл до `ManageVacancyTask`.
6. Создать кандидата через REST `/api/v1/auth/register/candidate`.
7. Создать отклик только через Camunda: стартовать `hhApplicationProcess`, заполнить `apply-to-vacancy-form`.
8. Зайти как рекрутер и выполнить `RecruiterDecisionTask`.
9. Подготовить приглашение через `invitation-form`.
10. Зайти как кандидат и выполнить `CandidateInvitationResponseTask`.
11. В Cockpit показать completed activities и variables.
12. Отдельно показать `hhTimeoutSchedulerProcess` и `hhAdminInterviewResetProcess` как процессы для периодической задачи и административной операции.
13. Закрыть вакансию и показать `MSG_VACANCY_CLOSED` / переход активных application process в ветку закрытия вакансии.

Сценарий защиты выполняется только через Camunda Tasklist, Cockpit, Admin и Forms; REST остаётся техническим API приложения, но не используется как демонстрационный интерфейс лабораторной.

## 11. Соответствие требованиям лабораторной

| Требование | Где реализовано |
|---|---|
| Camunda как BPM-движок | standalone container `camunda` в `docker-compose.yml` |
| BPMN 2.0 | `src/main/resources/camunda/*.bpmn` |
| Camunda Forms | `src/main/resources/camunda/forms/*.form` |
| UI через Camunda forms | Tasklist user tasks: vacancy, application, invitation, response, admin reset, result forms, `hh-ui-*.bpmn` query screens |
| Роли | `candidateStarterGroups` / `candidateGroups` в BPMN + DMN `hhOperationPermissions` + `CamundaIdentitySyncService` + Spring Security |
| DMN | `hh-operation-permissions.dmn`, `hh-auto-screening.dmn`, `hh-status-transitions.dmn`, `hh-notification-templates.dmn` |
| Транзакции | `bpmn:transaction` + error/cancel/compensation boundary events + explicit compensation throw events + `@Transactional` + Narayana/JTA |
| Асинхронная обработка | Camunda external tasks и worker |
| Периодические задачи | `hh-timeout-scheduler.bpmn` с timer start event; ручной review стартует тот же process definition по ключу и попадает в тот же BPMN loop |
| Уведомления как процесс | reusable `hhNotificationProcess`, вызывается через `bpmn:callActivity`, шаблоны выбираются через DMN |
| Межпроцессные сообщения | `MSG_VACANCY_CLOSED`, `MSG_INTERVIEW_CANCELLED`, `MSG_ADMIN_RESET_DONE`, `MSG_INVITATION_EXPIRED` |
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

## 13. Сценарий защиты для преподавателя

Этот раздел удобнее всего держать открытым во время показа. Идея демонстрации: сначала показать, что Camunda запущена отдельно и в ней лежат BPMN/forms, потом пройти один happy path, затем показать ограничения ролей, откаты и scheduler.

REST API в текущей версии защищён HTTP Basic, а не JWT. Поэтому в curl-командах используется `-u email:password`. Camunda Tasklist имеет отдельный логин: seed-пользователи приложения синхронизируются в Camunda как `recruiterexamplecom`, `adminexamplecom`, а кандидат — как email без спецсимволов.

### 13.1. Поднять стенд

```bash
docker compose down -v
docker compose up -d --build postgres camunda app
```

Проверить готовность:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8081/engine-rest/version
curl -fsS http://127.0.0.1:8081/engine-rest/process-definition/key/hhVacancyProcess
```

Открыть в браузере:

```text
Tasklist: http://127.0.0.1:8081/camunda/app/tasklist/default/
Cockpit:  http://127.0.0.1:8081/camunda/app/cockpit/default/
Admin:    http://127.0.0.1:8081/camunda/app/admin/default/
```

### 13.2. Что показать в Camunda Admin/Cockpit

1. `Admin -> Groups`: есть группы `CANDIDATE`, `RECRUITER`, `ADMIN`.
2. `Admin -> Users`: есть `recruiterexamplecom`, `adminexamplecom`, а после регистрации кандидата появится его Camunda user id.
3. `Cockpit -> Processes`: видны `hhVacancyProcess`, `hhApplicationProcess`, `hhTimeoutSchedulerProcess`, `hhRecruiterInterviewCancelProcess`, `hhAdminInterviewResetProcess` и `hhUi...` процессы.
4. Открыть BPMN XML или diagram и показать `candidateStarterGroups` на executable processes и `candidateGroups` на user tasks.
5. `Cockpit -> Decisions`: виден `HH operation permission matrix` / key `hhOperationPermissions`, version с `History Time To Live = 30`.
6. В основных BPMN показать `Evaluate...Permission` business rule tasks перед `CreateVacancyTask`, `RecruiterDecisionTask`, `CandidateInvitationResponseTask`, `AdminResetApprovalTask`.
7. В Tasklist показать filters: `Задачи кандидата`, `Задачи рекрутера`, `Задачи администратора`, `Мои активные задачи`.
8. В Camunda Modeler открыть любой `hh-ui-*.bpmn`, `hh-vacancy-process.bpmn`, `hh-application-process.bpmn` и `hh-notification-process.bpmn`: у файлов есть pool, lane, подписанные start/end и `bpmndi:BPMNDiagram`, поэтому они открываются как диаграммы.

### 13.3. Рекрутер создает вакансию только через Camunda

Ответ на частый вопрос: да, рекрутер сейчас может создавать вакансии. Но REST endpoint не делает `vacancyRepository.save(...)` напрямую. Он стартует `hhVacancyProcess`, а запись в БД создает external task `CreateVacancyFromForm`.

Java-сноска для показа: controller вызывает `VacancyService.create`, сервис стартует Camunda process через `CamundaWorkflowFacade.startVacancyCreateFromRequest`, worker забирает external task `CreateVacancyFromForm`, а `CamundaProcessAdapterService.createVacancyFromCamundaForm` уже под `@Transactional` сохраняет запись и обновляет business key.[^vacancy-service][^workflow-facade][^external-worker][^adapter]

Показ через REST:

```bash
curl -u recruiter@example.com:password123 \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Java Camunda Demo",
    "description": "Demo vacancy for lab defense",
    "required_skills": ["Java", "Spring", "Camunda"],
    "screening_threshold": 1
  }' \
  http://127.0.0.1:8080/api/v1/recruiters/vacancies
```

После ответа открыть Cockpit:

```text
Processes -> HH vacancy process with roles -> Instances
```

Что показать:

- process key: `hhVacancyProcess`;
- business key вида `vacancy:<vacancyId>`;
- activity history: `ValidateCreateVacancyForm`, `CreateVacancyFromForm`, `VacancyCreatedResultTask`;
- process variables: `restAutoSubmit=true`, `vacancyCreated=true`, `vacancyId`.

Показ только через Tasklist:

```text
login: recruiterexamplecom
password: camunda
Start process -> HH vacancy process with roles
```

Заполнить `create-vacancy-form`, завершить result task и показать тот же instance в Cockpit.

### 13.4. Кандидат создает отклик, но не вакансию и не отдельное резюме

В системе нет отдельного endpoint/process для сущности `resume`. Резюме здесь является полем `resumeText` внутри отклика. Кандидат может создать только отклик к активной вакансии, а вакансию создать не может.

Java-сноска для показа: право `VACANCY_CREATE` есть только у `RECRUITER`, а кандидат имеет `APPLICATION_CREATE`. Это видно в `RolePrivileges`, на controller-методах с `@PreAuthorize`, и в BPMN через `candidateStarterGroups`/`candidateGroups`.[^security][^bpmn-vacancy][^bpmn-application]

Зарегистрировать кандидата:

```bash
curl -X POST http://127.0.0.1:8080/api/v1/auth/register/candidate \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "candidate-demo@example.com",
    "password": "password123",
    "first_name": "Candidate",
    "last_name": "Demo"
  }'
```

Проверить, что кандидат не может создать вакансию:

```bash
curl -i -u candidate-demo@example.com:password123 \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Forbidden vacancy",
    "description": "Must fail",
    "required_skills": ["Java"],
    "screening_threshold": 1
  }' \
  http://127.0.0.1:8080/api/v1/recruiters/vacancies
```

Ожидаемый результат: `403 Forbidden`.

Создать отклик через Camunda:

1. Зайти в Tasklist как синхронизированный кандидат.
2. Стартовать `hhUiCandidateVacancyList` и показать список активных вакансий.
3. Стартовать `hhApplicationProcess`.
4. Заполнить `apply-to-vacancy-form`: `vacancyId`, `resumeText`, `coverLetter`.
5. Завершить задачу `ApplyToVacancyTask`.

В Cockpit показать `hhApplicationProcess`:

- `CreateApplicationFromForm` создал отклик;
- `AutoScreenApplication` подготовил score/threshold, `EvaluateAutoScreeningDecision` вызвал DMN `hhAutoScreening`, `SaveAutoScreenDecision` сохранил результат;[^autoscreen-dmn]
- `NotifyRecruiter` является `callActivity`, вызывает `hhNotificationProcess`, а внутри notification subprocess DMN `hhNotificationTemplates` выбирает `type/template/recipientRole`;[^notification-process][^notification-dmn]
- перед `RecruiterDecisionTask` выполнены `ResolveRecruiterDecisionPermission` и `EvaluateRecruiterDecisionPermission`, а DMN вернула `permissionAllowed=true`;
- рекрутер получил `RecruiterDecisionTask`;
- права на candidate/recruiter tasks разделены через `candidateGroups`.

### 13.5. Разграничение ролей

Что можно показать быстро:

```bash
# кандидат не может создать вакансию
curl -i -u candidate-demo@example.com:password123 http://127.0.0.1:8080/api/v1/recruiters/vacancies

# рекрутер не может выполнить admin job
curl -i -u recruiter@example.com:password123 -X POST \
  http://127.0.0.1:8080/api/v1/admin/jobs/close-expired-invitations

# админ не является рекрутером и не может создать вакансию
curl -i -u admin@example.com:password123 \
  -H 'Content-Type: application/json' \
  -d '{"title":"Admin vacancy","description":"","required_skills":["Java"],"screening_threshold":1}' \
  http://127.0.0.1:8080/api/v1/recruiters/vacancies
```

В Tasklist показать то же самое визуально:

- `recruiterexamplecom` видит recruiter tasks и не видит candidate response task как свою групповую задачу;
- `adminexamplecom` видит admin timeout/reset tasks;
- кандидат видит candidate tasks и UI-экраны кандидата.

В Cockpit/History показать DMN-переменные на instance:

```text
permissionRole
permissionOperation
permissionOwnership
permissionAllowed
```

Java-сноска для показа: worker обрабатывает topic `permission-check`, вызывает `resolveCreateVacancyPermission`, `resolveRecruiterDecisionPermission`, `resolveCandidateResponsePermission` или `resolveAdminResetPermission`, а затем business rule task `Evaluate...Permission` вызывает DMN `hhOperationPermissions`.[^permission-dmn][^external-worker][^adapter]

### 13.6. Транзакции и откаты

Формулировка для защиты: Camunda управляет бизнес-транзакцией на BPMN-уровне через `bpmn:transaction`, error boundary events, cancel boundary events, compensation boundary events, explicit compensation throw events и recovery/compensation service tasks. Физические DB-изменения выполняются external task adapter-методами под `@Transactional`/Narayana/JTA. Распределенные транзакции переносить на BPM-движок по ТЗ не требуется.

Важно проговорить: `bpmn:transaction` здесь показывает и оркестрирует бизнес-границу операции. Атомарность SQL-изменений обеспечивает Spring transaction manager/Narayana в Java-методах adapter/service layer. Если Java-операция падает, worker кидает BPMN error через Camunda REST, и процесс уходит на error boundary/recovery task.[^external-worker][^adapter][^jta]

Что показать в BPMN:

- `hh-application-process.bpmn`: `Transaction_Rejection`, `Transaction_Invitation`, `Transaction_Response`, error boundaries, `Cancel_Transaction_*`, `Compensate_Transaction_*`, `ThrowCompensationApplicationRollback`;
- `hh-vacancy-process.bpmn`: `Transaction_CloseVacancy`, `RollbackVacancyTransaction`, `Cancel_Transaction_CloseVacancy`, `Compensate_Transaction_CloseVacancy`, `ThrowCompensationVacancyRollback`;
- `hh-recruiter-interview-cancel.bpmn`: `Transaction_RecruiterCancelInterview`, cancel/compensation boundary events;
- `hh-admin-interview-reset.bpmn`: `Transaction_AdminInterviewReset`, cancel/compensation boundary events;
- `hh-timeout-scheduler.bpmn`: `Transaction_TimeoutOneInvitation`, cancel/compensation boundary events.

Что запустить для доказательства:

```bash
python test/test_transaction_atomicity.py
python test/test_composite_transactions.py
```

Или в Docker:

```bash
docker build -t hh-process-api-tests ./test
docker run --rm --network host hh-process-api-tests
```

Эти тесты проверяют, что при конфликте/ошибке не остается частично созданных интервью, слотов, статусов и уведомлений, а составные операции ведут себя атомарно.

### 13.7. Scheduler через Camunda timer

Открыть `hhTimeoutSchedulerProcess` в Cockpit и показать timer start event / timer job. Для timer start event нормально, что прямо сейчас может не быть active process instance: Camunda держит scheduled job и создаёт instance, когда наступает `dueDate`. На локалке и Helios локальный Spring scheduler выключен:

```text
APP_TIMEOUT_LOCAL_SCHEDULER_ENABLED=false
```

Проверка переменной:

```bash
docker compose exec app printenv APP_TIMEOUT_LOCAL_SCHEDULER_ENABLED
```

Проверка timer job через REST:

```bash
curl -fsS 'http://127.0.0.1:8081/engine-rest/job?processDefinitionKey=hhTimeoutSchedulerProcess'
```

Ручной запуск для показа через Camunda UI:

```text
Tasklist -> login adminexamplecom / camunda
Start process -> HH UI admin timeout review
Submit -> DisplayTimeoutReview
```

В Cockpit показать activity `RunTimeoutReview`, переменные `schedulerProcessStarted=true`, `owner=Camunda BPMN loop hhTimeoutSchedulerProcess`, запущенный instance `hhTimeoutSchedulerProcess` и отсутствие incidents.

Java-сноска для показа: `CamundaDeploymentService` деплоит timer process и пробует стартовать/актуализировать scheduler, `CamundaExternalTaskWorker` обрабатывает external tasks после срабатывания timer, а локальный Spring scheduler в `TimeoutService` выключен флагом и оставлен только как fallback.[^deployment][^external-worker][^timeout]

### 13.8. Полный локальный прогон тестов

Unit tests:

```bash
mvn test
```

Интеграционный Docker-прогон:

```bash
docker compose down -v
docker compose up -d --build postgres camunda app

docker build -t hh-process-api-tests ./test
docker run --rm --network host \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e CAMUNDA_URL=http://127.0.0.1:8081/engine-rest \
  -e POSTGRES_HOST=127.0.0.1 \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_SCHEMA=public \
  hh-process-api-tests
```

Отдельно Camunda-level smoke:

```bash
CAMUNDA_URL=http://127.0.0.1:8081/engine-rest \
BASE_URL=http://127.0.0.1:8080 \
POSTGRES_HOST=127.0.0.1 \
POSTGRES_PORT=5432 \
POSTGRES_DB=postgres \
POSTGRES_USER=postgres \
POSTGRES_PASSWORD=postgres \
POSTGRES_SCHEMA=public \
python test/test_camunda_smoke_flow.py
```

В этом smoke проверяются именно Camunda users/groups, process instances, active tasks, history activities, variables, UI processes и отсутствие incidents.

Отдельные проверки, которые удобно назвать преподавателю:

```bash
python test/test_access_matrix.py
python test/test_transaction_atomicity.py
python test/test_timeout_job_db_fixture.py
python test/test_camunda_model_coverage.py
python test/test_camunda_visual_model_contract.py
python test/test_camunda_integration.py
python test/test_camunda_decisions_runtime.py
python test/test_camunda_tasklist_candidate_apply.py
```

### 13.9. Helios

В split deploy GitHub Actions Camunda разворачивается как standalone Tomcat-сервис, а приложение как WAR под WildFly. На что обратить внимание перед защитой:

- в app env должно быть `CAMUNDA_ENABLED=true`;
- worker должен быть включен: `CAMUNDA_WORKER_ENABLED=true`;
- app должен смотреть на standalone Camunda REST: `CAMUNDA_BASE_URL=http://127.0.0.1:<CAMUNDA_HTTP_PORT>/engine-rest`;
- локальный scheduler должен быть выключен: `APP_TIMEOUT_LOCAL_SCHEDULER_ENABLED=false`;
- после старта Camunda вызывается `camundactl.sh cleanup-demo`, чтобы удалить стандартные demo deployments/forms/filters Camunda (`invoice`, `ReviewInvoice`, demo filters).

## 14. Что ещё можно перенести из Java в Camunda

Это не обязательно для запуска текущей версии, но полезно предложить преподавателю как план усиления работы. Чем больше правил становится видимыми в BPMN/DMN, тем лучше видно, что BPMS действительно управляет процессом.

1. **Расширить DMN-таблицу автоскрининга.** Базовая таблица `hh-auto-screening.dmn` уже реализована: Java считает технические входы `screeningScore/screeningThreshold`, а Camunda DMN принимает решение `screeningPassed`. Дальше можно добавить веса навыков, уровни seniority и стоп-слова.

2. **Более подробная DMN-матрица прав.** Базовая таблица `role + operation + ownership -> allowed` уже реализована в `hh-operation-permissions.dmn`. Дальше можно расширить её строками для закрытия вакансии, изменения статуса, просмотра списков и ручных UI-процессов, чтобы вообще вся матрица авторизации была видна в Cockpit Decisions.

3. **Расширить state machine статусов через BPMN/DMN.** Базовая таблица `hh-status-transitions.dmn` уже реализована: `currentStatus + action + requestedStatus -> allowed/nextStatus`. Дальше можно добавить отдельные действия для admin timeout review, recruiter cancel и read-only UI переходов.

4. **Расширить DMN-шаблоны уведомлений.** Базовая таблица `hh-notification-templates.dmn` уже реализована: `notificationKind + status + recipientRole -> template/type`. Дальше можно добавить локализацию, разные каналы доставки и priority.

5. **Детализировать compensation handlers.** Явные compensate throw events уже добавлены в rollback-ветки. Дальше можно разнести компенсацию по отдельным handler tasks: release slot, cancel interview, restore application status, restore vacancy status.

6. **Усилить Camunda-first UI для кандидата.** Путь кандидата уже можно показать через `hhUiCandidateVacancyList` и старт `hhApplicationProcess` с `apply-to-vacancy-form`. Дальше можно добавить отдельную форму выбора вакансии, которая автоматически передаёт `vacancyId` в процесс отклика.

7. **Расширить межпроцессные сообщения.** Уже есть `MSG_VACANCY_CLOSED`, `MSG_INTERVIEW_CANCELLED`, `MSG_ADMIN_RESET_DONE`, `MSG_INVITATION_EXPIRED`. Дальше можно добавить сообщения для изменения вакансии и системной блокировки пользователя.

8. **Scheduler batch как multi-instance.** `hh-timeout-scheduler.bpmn` уже содержит timer start и BPMN loop обработки просроченных приглашений; ручной UI review запускает тот же process definition по ключу. Дальше можно заменить цикл `find one -> process one -> repeat` на BPMN multi-instance по списку найденных просрочек.

Что не стоит переносить: JTA/Narayana, repository calls, блокировки `findByIdForUpdate`, реальные SQL-операции и XA/prepared transactions. По ТЗ распределённые транзакции на BPM-движок переносить не требуется; Camunda должна оркестрировать процесс, а Java должна надежно выполнять технические операции.

## 15. Сноски к Java/BPMN-коду

[^deployment]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaDeploymentService.java` — `deployOnStartup()` срабатывает на `ApplicationReadyEvent`: ждёт Camunda REST, удаляет demo-процессы/filters, сканирует `classpath*:camunda/*.bpmn`, `classpath*:camunda/*.dmn` и `classpath*:camunda/forms/*.form`, деплоит их, вызывает `CamundaIdentityProviderService.provisionApplicationIdentity()` и актуализирует timeout scheduler. `CamundaRestClient.deploy()` создаёт новый deployment без duplicate-filtering, чтобы standalone Camunda всегда показывала latest BPMN/DMN из текущего WAR.

[^workflow-facade]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaWorkflowFacade.java` — сервисы приложения обращаются сюда, чтобы start/complete Camunda processes/tasks. Ключевые методы: `startVacancyCreateFromRequest`, `startApplicationCreateFromRequest`, `recruiterInvited`, `invitationResponded`, `closeVacancy`, `startTimeoutSchedulerIfNeeded`. Здесь же есть ownership checks `assertRecruiterCanComplete`, `assertCandidateCanComplete`, `assertVacancyRecruiterCanComplete`, а внешние события коррелируются сообщениями `MSG_INTERVIEW_CANCELLED`, `MSG_ADMIN_RESET_DONE`, `MSG_INVITATION_EXPIRED`.

[^external-worker]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaExternalTaskWorker.java` — `poll()` делает fetch-and-lock external tasks, `handleTask()` маршрутизирует по topic/activityId. Новые topics: `permission-check` подготавливает переменные для DMN прав, `status-transition` подготавливает state machine DMN, `notification-decision` подготавливает DMN-шаблон уведомления, `notification-dispatch` обслуживает reusable `hhNotificationProcess`. `throwFormValidationBpmnError()` кидает `FORM_VALIDATION_FAILED`, а `throwRollbackBpmnError()` кидает `APPLICATION_TX_FAILED`, `VACANCY_TX_FAILED` или `ADMIN_RESET_FAILED`, чтобы BPMN ушёл на boundary event/recovery.

[^adapter]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaProcessAdapterService.java` — основной adapter между BPMN service tasks и Java domain layer. Методы `resolveCreateVacancyPermission`, `resolveRecruiterDecisionPermission`, `resolveCandidateResponsePermission`, `resolveAdminResetPermission` готовят DMN-входы прав; `prepareAutoScreen`/`saveAutoScreenDecision` разделяют Java-подготовку score и Camunda DMN-решение; `prepare...Transition` готовят входы state machine; `prepareNotificationDecision`/`dispatchNotification` создают уведомления по DMN-шаблону. Методы валидации используют `CamundaFormValidator`, а методы `createVacancyFromCamundaForm`, `createApplicationFromCamundaForm`, `markApplicationRejected`, `createInvitationInterview`, `reserveInvitationSlot`, `markVacancyClosed`, `closeActiveApplicationsForVacancy`, `runTimeoutReview` помечены `@Transactional` или вызывают transactional services.

[^vacancy-service]: `src/main/java/ru/itmo/hhprocess/service/VacancyService.java` — `create` и `updateStatus` не являются основной бизнес-логикой сохранения. Они стартуют Camunda process через facade и ждут переменные результата worker-а; запись создаёт BPMN service task `CreateVacancyFromForm`.

[^application-service]: `src/main/java/ru/itmo/hhprocess/service/ApplicationService.java` — `create` стартует `hhApplicationProcess` через `startApplicationCreateFromRequest` и ждёт результата `CreateApplicationFromForm`, поэтому отклик создаётся из процесса, а не прямым save из controller.

[^interview-process]: `src/main/java/ru/itmo/hhprocess/service/InterviewProcessService.java` — invite/reject/admin reset/recruiter cancel двигают Camunda tasks/processes через facade и ждут итоговые статусы, чтобы REST-ответ соответствовал завершённой стадии процесса.

[^timeout]: `src/main/java/ru/itmo/hhprocess/service/TimeoutService.java` и `src/main/java/ru/itmo/hhprocess/service/TimeoutBatchProcessor.java` — `TimeoutService.closeExpiredInvitations()` имеет `@Scheduled`, но локальный scheduler выключается `APP_TIMEOUT_LOCAL_SCHEDULER_ENABLED=false`. Batch-шаги вызываются из `hh-timeout-scheduler.bpmn` через external tasks, а ручной запуск `hhUiAdminTimeoutReview` стартует тот же scheduler process definition по ключу, поэтому fallback на Spring batch не нужен.

[^security]: `src/main/java/ru/itmo/hhprocess/security/SecurityConfig.java`, `src/main/java/ru/itmo/hhprocess/security/RolePrivileges.java`, `src/main/java/ru/itmo/hhprocess/security/Privilege.java`, controllers в `src/main/java/ru/itmo/hhprocess/controller` — REST-уровень защищён HTTP Basic, authorities и `@PreAuthorize`; ownership дополнительно проверяется в facade/adapter.

[^identity]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaIdentitySyncService.java` и `src/main/java/ru/itmo/hhprocess/service/CandidateRegistrationFinalizer.java` — пользователи приложения синхронизируются в Camunda users/groups; новый кандидат получает Camunda password из регистрации.

[^identity-provider]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaIdentityProviderService.java` — единая точка provisioning для standalone Camunda: start authorizations, users/groups/memberships и Tasklist filters. Это внешний identity provider через Camunda REST, а не embedded plugin внутри движка.

[^task-listener]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaTaskListenerAdapter.java` — внешний task listener adapter: читает активные user tasks через REST (`created desc`, до 500 задач за проход), определяет владельца по process variables/starter, проверяет candidate group, назначает `assignee` и создаёт task authorization.

[^filters]: `src/main/java/ru/itmo/hhprocess/camunda/CamundaTasklistFilterService.java` — создает Tasklist filters через Camunda REST.

[^bpmn-vacancy]: `src/main/resources/camunda/hh-vacancy-process.bpmn` — BPMN создания/закрытия вакансии, transaction close, message correlation.

[^bpmn-application]: `src/main/resources/camunda/hh-application-process.bpmn` — BPMN отклика, автоскрининга, решения рекрутера, приглашения, ответа кандидата, timer boundary, message boundary, DMN permission checks, notification call activities и transaction compensation/cancel elements.

[^bpmn-scheduler]: `src/main/resources/camunda/hh-timeout-scheduler.bpmn` — timer start process для периодического закрытия просроченных приглашений.

[^bpmn-visual-test]: `test/test_camunda_visual_model_contract.py` — статический тест, который проверяет, что каждый BPMN имеет pool/participant, lane, подписанные start/end events, BPMNShape для flow nodes и BPMNEdge для sequence flows.

[^permission-dmn]: `src/main/resources/camunda/hh-operation-permissions.dmn` — DMN decision table `hhOperationPermissions`: входы `permissionRole`, `permissionOperation`, `permissionOwnership`, выход `allowed`; в BPMN результат сохраняется в `permissionAllowed`.

[^autoscreen-dmn]: `src/main/resources/camunda/hh-auto-screening.dmn` — DMN decision table `hhAutoScreening`: вход `screeningScoreDelta = screeningScore - screeningThreshold`, выход `passed`; BPMN сохраняет его как `screeningPassed`, а Java только сохраняет результат и переводит статус.

[^status-dmn]: `src/main/resources/camunda/hh-status-transitions.dmn` — DMN decision table `hhStatusTransitions`: входы `currentStatus`, `statusAction`, `requestedStatus`, выходы `allowed`, `nextStatus`; BPMN использует gateway по `statusTransition.allowed`.

[^notification-dmn]: `src/main/resources/camunda/hh-notification-templates.dmn` — DMN decision table `hhNotificationTemplates`: входы `notificationKind`, `notificationStatus`, `recipientRole`, выход `templateCode` в формате `kind|type|recipientRole|template`; Java парсит это решение и создаёт уведомления.

[^notification-process]: `src/main/resources/camunda/hh-notification-process.bpmn` — reusable process `hhNotificationProcess` с `PrepareNotificationTemplateDecision`, business rule task `EvaluateNotificationTemplate` и external task `DispatchNotification`. Основные процессы вызывают его через `bpmn:callActivity` (`NotifyRecruiter`, `NotifyInvitation`, `NotifyRejection`, `NotifyCandidateResponse`, timeout/admin/vacancy notifications).

[^jta]: `src/main/java/ru/itmo/hhprocess/config/NarayanaJtaConfig.java`, `src/main/resources/application.yml`, `docker-compose.yml` — JTA включён через Spring/Narayana, Hibernate работает с `transaction.coordinator_class=jta`, PostgreSQL в Docker стартует с `max_prepared_transactions=200` и `max_connections=200`, Narayana пишет object store в `NARAYANA_LOG_DIR`.

[^helios-demo-cleanup]: `.github/workflows/deploy-split-helios.yml` и `infra/helios-split/camundactl.sh` — split deploy, env для WildFly/Camunda, cleanup demo deployments/filters на Helios.

[^camunda-smoke-test]: `test/test_camunda_smoke_flow.py` — интеграционный smoke на уровне Camunda REST: проверяет users/groups, instances, history activities, user tasks, variables, UI processes и incidents.
