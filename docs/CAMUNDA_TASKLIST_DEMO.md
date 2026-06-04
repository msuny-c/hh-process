# Camunda Tasklist-only demo

Этот сценарий показывает лабораторную без Swagger/Postman: все пользовательские действия выполняются через Camunda Tasklist и Camunda Forms.

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

## 2. Просмотр UI-экранов через Camunda Forms

1. `Start process` -> `HH UI recruiter vacancy list`.
2. Открыть task `Список вакансий рекрутера`.
3. Показать readonly JSON с вакансиями.
4. Аналогично можно показать:
   - `HH UI recruiter application list`;
   - `HH UI recruiter schedule`;
   - `HH UI notification list`.

## 3. Отклик кандидата

1. Войти в Tasklist как кандидат.
2. `Start process` -> `HH application process with roles`.
3. В форме `apply-to-vacancy-form` указать `vacancyId`, резюме и сопроводительное письмо.
4. В Cockpit показать external tasks: `application-persistence`, `application-auto-screening`, `application-notification`.
5. Если скрининг пройден, рекрутер увидит task `Рассмотреть отклик рекрутером`.

## 4. Решение рекрутера и приглашение

1. Войти как `recruiterexamplecom / camunda`.
2. Открыть `Рассмотреть отклик рекрутером`.
3. Выбрать `INVITE`.
4. Открыть `Подготовить приглашение на интервью`.
5. Заполнить текст, `scheduledAt` в ISO-8601 и длительность.
6. В Cockpit показать transaction block `TX: оформление приглашения`.

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
4. Показать result form с `closedCount`.

## 8. Что показать в Cockpit

- `candidateStarterGroups` и `candidateGroups` на BPMN tasks.
- External task topics:
  - `application-persistence`;
  - `application-notification`;
  - `application-message`;
  - `timeout-close-expired`;
  - `vacancy-close-applications`.
- Timer start event в `HH timeout scheduler with roles`.
- Message correlation `MSG_VACANCY_CLOSED`.
- Transaction subprocesses и error boundary events.
