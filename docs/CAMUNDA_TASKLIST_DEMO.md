# Camunda Tasklist-only demo

Этот сценарий показывает лабораторную через Camunda Tasklist и Camunda Forms. REST-уровень остаётся служебным API приложения, но пользовательский путь защиты выполняется в Camunda.

## Подготовка

```bash
docker compose down -v
docker compose up --build
```

Открыть:

```text
http://localhost:8081/camunda/app/tasklist/default/
http://localhost:8081/camunda/app/cockpit/default/
```

Пользователи Camunda:

```text
admin / admin
recruiterexamplecom / camunda
adminexamplecom / camunda
```

Кандидата можно создать заранее через приложение или использовать уже синхронизированного кандидата после регистрации. Для Tasklist user id равен email без символов кроме латинских букв и цифр.

## 1. Создание вакансии рекрутером

1. Войти в Tasklist как `recruiterexamplecom / camunda`.
2. `Start process` -> `HH vacancy process with roles`.
3. Заполнить форму `create-vacancy-form`.
4. Завершить result task `Показать результат создания вакансии`.
5. В Cockpit открыть instance и показать `vacancyId`, `status=ACTIVE`, business key `vacancy:<id>`.
6. В Tasklist показать, что задача назначена `recruiterexamplecom`: это сделал внешний `CamundaTaskListenerAdapter`.

Проверка validation loop: при старте `HH vacancy process with roles` оставить `title` пустым или `requiredSkills` пустым, завершить форму и показать, что BPMN error `FORM_VALIDATION_FAILED` вернул процесс обратно на `Заполнить форму вакансии`, а задача не исчезла.

## 2. Просмотр UI-экранов через Camunda Forms

1. `Start process` -> `HH UI recruiter vacancy list`.
2. Открыть task `Список вакансий рекрутера`.
3. Показать readonly JSON с вакансиями.
4. Аналогично можно показать:
   - `HH UI recruiter application list`;
   - `HH UI recruiter schedule`;
   - `HH UI notification list`.

Для `HH UI recruiter schedule` Java отдаёт в Camunda Form компактный payload: неделя, `total_items` и ключевые поля слотов (`start_at`, `end_at`, `application_id`, `candidate_email`, `interview_status`). Это сделано специально, чтобы JSON помещался в строковую переменную Camunda Tasklist и не обрезался на загрязнённом стенде.

## 3. Отклик кандидата

1. Войти в Tasklist как кандидат.
2. `Start process` -> `HH application process with roles`.
3. В форме `apply-to-vacancy-form` указать `vacancyId`, резюме и сопроводительное письмо.
4. В Cockpit показать external tasks: `application-persistence`, `application-auto-screening`, `notification-decision`, `notification-dispatch`.
5. В Cockpit Decisions открыть `hhAutoScreening`: Java подготовила score/threshold, а решение `screeningPassed` приняла DMN.
6. Если скрининг пройден, рекрутер увидит task `Рассмотреть отклик рекрутером`.
7. В Tasklist показать assignee кандидата на кандидатских задачах и assignee рекрутера на `Рассмотреть отклик рекрутером`.

## 4. Решение рекрутера и приглашение

1. Войти как `recruiterexamplecom / camunda`.
2. Открыть `Рассмотреть отклик рекрутером`.
3. Выбрать `INVITE`.
4. Открыть `Подготовить приглашение на интервью`.
5. Заполнить текст, `scheduledAt` в ISO-8601 и длительность.
6. В Cockpit показать transaction block `TX: оформление приглашения`.
7. Ошибочный `scheduledAt` в прошлом должен вернуть процесс на форму `Подготовить приглашение на интервью`.

## 5. Ответ кандидата

1. Войти как кандидат.
2. Открыть `Ответить на приглашение`.
3. Выбрать `ACCEPT`, `DECLINE` или `OTHER`.
4. Завершить result task.
5. В Cockpit показать итоговый статус и историю activity instances.

## 6. Отмена интервью рекрутером

1. Войти как рекрутер.
2. `Start process` -> `HH recruiter interview cancel`.
3. Указать `interviewId` и причину отмены.
4. Показать transaction block: отмена интервью, освобождение слота, возврат отклика на рассмотрение.

## 7. Admin timeout review

1. Войти как `adminexamplecom / camunda`.
2. `Start process` -> `HH UI admin timeout review`.
3. Подтвердить ручную проверку.
4. Показать result form с `schedulerProcessStarted=true`, `owner=Camunda BPMN loop hhTimeoutSchedulerProcess` и `processInstanceId` запущенного scheduler process.
5. В Cockpit открыть `HH timeout scheduler with roles` и показать timer start + BPMN loop обработки одной просрочки.

## 8. Что показать в Cockpit

- `candidateStarterGroups` и `candidateGroups` на BPMN tasks.
- Pool/lane layout: каждый BPMN открывается в Modeler как диаграмма, все start/end events подписаны.
- Decisions:
  - `hhOperationPermissions`;
  - `hhAutoScreening`;
  - `hhStatusTransitions`;
  - `hhNotificationTemplates`.
- External task topics:
  - `application-persistence`;
  - `application-auto-screening`;
  - `status-transition`;
  - `notification-decision`;
  - `notification-dispatch`;
  - `application-message`;
  - `timeout-close-expired`;
  - `vacancy-close-applications`.
- Timer start event в `HH timeout scheduler with roles`.
- Message correlation `MSG_VACANCY_CLOSED`, `MSG_INTERVIEW_CANCELLED`, `MSG_ADMIN_RESET_DONE`, `MSG_INVITATION_EXPIRED`.
- Transaction subprocesses, error/cancel boundary events и explicit compensation throw events.
- Call activity `Call: ...` ссылается на reusable process `hhNotificationProcess`. В Modeler он открывается как отдельный BPMN-файл `hh-notification-process.bpmn`; сами transaction subprocesses нарисованы expanded.

## 9. Быстрая проверка моделей перед показом

```bash
python3 test/test_camunda_visual_model_contract.py
python3 test/test_camunda_model_coverage.py
```

Первый тест дополнительно ловит DI-ошибку, из-за которой Camunda engine отклоняет deployment: внутри `BPMNEdge` все `di:waypoint` должны идти перед `bpmndi:BPMNLabel`.

Первый тест проверяет, что все BPMN имеют pool, lane, подписанные start/end events и полный DI-слой. Второй проверяет DMN/BPMN coverage: decisions, messages, compensation и отсутствие scheduler fallback.

Дополнительно `mvn test` проверяет Java-часть Camunda-интеграции: identity provider provisioning, task listener adapter и единый `CamundaFormValidator`.
