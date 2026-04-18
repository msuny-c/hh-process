#import "@preview/codly:1.3.0": *
#import "@preview/codly-languages:0.1.1": *
#import "@preview/sicons:16.0.0": *
#import "title.typ": titlepage

#set page(margin: 1.7cm)
#set text(region: "RU", lang: "RU")
#set par(justify: true, leading: 0.7em)

#show link: set text(fill: blue)
#show link: underline
#show: codly-init.with()
#codly(languages: codly-languages)
#codly(zebra-fill: none)

#titlepage(
  lab_no: 3,
  subject: "бизнес-логика программных систем",
  variant: "3212",
  student: "Григорий Садовой\nБайрамгулов Мунир",
  teacher: "Кривоносов Егор Дмитриевич",
)

= Условие задания

Лабораторная работа №3 является развитием проекта ЛР2 по процессу обработки откликов на вакансии hh.ru.

В доработанной версии необходимо было реализовать:

- асинхронную обработку через Kafka и ZooKeeper;
- обработку сообщений минимум на двух независимых узлах;
- consumers на Spring `@KafkaListener`;
- отправку сообщений через Kafka Producer API;
- периодические задачи через `@Scheduled`;
- интеграцию с внешней EIS через JCA;
- распределённую транзакцию минимум между двумя XA-ресурсами.

В качестве бизнес-процесса сохранён процесс обработки отклика на вакансию с последующим screening, приглашением на интервью, работой с расписанием рекрутера и экспортом интервью во внешнюю корпоративную систему календаря.

= Модель процесса

#image("HH.ru.png")

В ЛР3 исходный процесс был расширен следующими шагами:

1. кандидат подаёт отклик;
2. API сохраняет заявку со статусом `SCREENING_IN_PROGRESS`;
3. после commit публикуется Kafka-событие `application.submitted`;
4. один из worker-узлов выполняет screening без записи результата в БД и публикует `application.screened`;
5. узел `api` потребляет `application.screened`, сохраняет `ScreeningResultEntity`, обновляет статус заявки и при необходимости создаёт уведомления (без отдельного Kafka-топика для уведомлений);
6. timeout scheduler закрывает просроченные приглашения;
7. export scheduler выбирает интервью и публикует `interview.export.requested`;
8. `eis-worker` экспортирует интервью во внешнюю EIS через JCA.

= Архитектура развёртывания

Система разворачивается в Docker Compose и состоит из следующих контейнеров:

- `postgres-main` — основная БД приложения;
- `postgres-schedule` — отдельная БД расписания;
- `zookeeper`;
- `kafka`;
- `app-api`;
- `app-worker`;
- `app-eis-worker`;
- внешняя EIS календаря собеседований, представленная учебным JCA adapter'ом.

Один и тот же `jar` запускается в разных ролях через `APP_ROLE`.
Для каждого инстанса приложения задан уникальный `NARAYANA_NODE_IDENTIFIER`, что необходимо для корректной работы Narayana при многовузловом запуске.

= Пакетная структура

К существующим пакетам ЛР2 были добавлены:

- `ru.itmo.hhprocess.messaging.config`
- `ru.itmo.hhprocess.messaging.dto`
- `ru.itmo.hhprocess.messaging.producer`
- `ru.itmo.hhprocess.messaging.consumer`
- `ru.itmo.hhprocess.integration.eis`
- `ru.itmo.hhprocess.integration.eis.jca`
- `ru.itmo.hhprocess.scheduler`
- `ru.itmo.hhprocess.schedule`
- `ru.itmo.hhprocess.tx`

Это позволило развести API-логику, consumer-side обработку, EIS integration и отдельный schedule-модуль на второй БД.

= Основные use cases

== Асинхронный screening

- Актор: кандидат.
- Вход: `POST /api/v1/candidates/vacancies/{vacancyId}`.
- Результат: создаётся заявка со статусом `SCREENING_IN_PROGRESS`.
- После commit: публикуется `application.submitted`.
- Worker получает событие, вычисляет результат screening и публикует `application.screened`.
- Узел `api` применяет событие к БД и переводит заявку в `ON_RECRUITER_REVIEW` либо `SCREENING_FAILED`.

== Уведомления

- После commit транзакции вызывается `NotificationAfterCommitService`, который создаёт запись в `notifications` (на узле `api` дополнительно срабатывает WebSocket push).

== Автоматическое закрытие просроченных приглашений

- Используется `TimeoutService` и `TimeoutBatchProcessor`.
- Задача запускается через `@Scheduled`.
- Scheduler включён только на узле `api`, что исключает дублирование обработки.

== Плановый экспорт интервью во внешнюю EIS

- `InterviewExportScheduler` периодически ищет назначенные интервью на ближайший диапазон.
- Для каждого интервью публикуется `interview.export.requested`.
- `eis-worker` экспортирует интервью через JCA-клиент `CalendarEisClient`.

== Распределённая транзакция приглашения на интервью

Сценарий `invite(...)` был выбран как основной distributed transaction use case.

В рамках одной JTA/XA-транзакции выполняются:

1. проверка бизнес-условий;
2. перевод заявки в `INVITED`;
3. создание `InterviewEntity` в main DB;
4. резервирование слота в schedule DB;
5. запись history;
6. создание уведомлений после commit (без Kafka-топика для уведомлений).

Если шаг резервирования слота завершается ошибкой, Narayana откатывает изменения в обеих БД.

= Асинхронное взаимодействие через Kafka

В приложении введены события:

- `ApplicationSubmittedEvent`
- `ApplicationScreenedEvent`
- `InterviewExportRequestedEvent`

Отправка сообщений реализована через чистый Kafka Producer API (`Producer<String, String>` / `KafkaProducer<String, String>`), а не через `KafkaTemplate`.

Для сохранения правила "публиковать событие только после успешного commit" введён helper `AfterCommitEventPublisher`, использующий `TransactionSynchronizationManager.registerSynchronization(...)`.

Получение сообщений реализовано через Spring `@KafkaListener`.

= Идемпотентность Kafka

Для защиты от повторной доставки сообщений создана таблица `processed_kafka_events`.

Сохраняются:

- `event_id`
- `topic`
- `processed_at`
- `consumer_name`

Перед обработкой consumer проверяет, не было ли событие уже обработано.
После успешной обработки создаётся запись в `processed_kafka_events`.
Поле `consumer_name` позволяет показать, что события обрабатывались разными worker-узлами.

= Вторая БД и XA

В проект добавлена отдельная schedule DB.

В main DB остаются:

- `users`
- `vacancies`
- `applications`
- `interviews`
- `notifications`
- `application_status_history`
- `screening_results`
- `invitation_responses`
- `processed_kafka_events`
- `interview_export_log`

В schedule DB вынесена таблица:

- `recruiter_schedule_slots`

Для второй БД создан отдельный XA datasource и отдельная persistence unit.
Таким образом, распределённая транзакция выполняется между двумя XA-ресурсами PostgreSQL и координируется Narayana с использованием prepared transactions.

= JCA интеграция с внешней EIS

Для демонстрации интеграции реализован учебный JCA resource adapter "Корпоративный календарь собеседований".

Реализованные классы:

- `CalendarManagedConnectionFactory`
- `CalendarManagedConnection`
- `CalendarConnectionFactory`
- `CalendarConnection`
- `CalendarInteraction`
- `CalendarInteractionSpec`

Через `CalendarEisClient` доступны операции:

- `createInterviewRecord(...)`
- `cancelInterviewRecord(...)`
- `getInterviewRecord(...)`

Факт экспорта фиксируется в таблице `interview_export_log` со статусами `PENDING`, `EXPORTED`, `FAILED`, `CANCELLED`.

= REST API

Ключевое изменение контракта по сравнению с ЛР2 относится к подаче отклика.

Теперь `POST /api/v1/candidates/vacancies/{vacancyId}` возвращает не финальный результат screening; для кандидата статус формулируется как заявка подана:

```json
{
  "application_id": "...",
  "status": "APPLICATION_SUBMITTED",
  "message": "Application submitted"
}
```

Остальные endpoint'ы сохранены, включая:

- Basic Auth;
- API кандидата;
- API рекрутера;
- admin endpoint'ы;
- расписание рекрутера;
- уведомления.

= Периодические задачи

В системе присутствуют два scheduler use case:

- `TimeoutService.closeExpiredInvitations()` — закрытие просроченных приглашений;
- `InterviewExportScheduler.scheduleInterviewExport()` — постановка интервью в очередь на экспорт.

Обе задачи реализованы через Spring `@Scheduled`.

= Выводы

В ходе ЛР3 проект был существенно расширен по сравнению с ЛР2.

Результаты работы:

- синхронный screening был переведён на асинхронную Kafka-обработку;
- обеспечена многовузловая обработка сообщений двумя независимыми worker-узлами;
- внедрена идемпотентность Kafka consumers;
- сохранён и расширен механизм Narayana JTA/XA;
- реализована распределённая транзакция между двумя PostgreSQL БД;
- добавлены scheduler use cases;
- выполнена интеграция с внешней EIS через JCA;
- актуализированы инфраструктура и документация проекта.

Таким образом, система теперь демонстрирует асинхронность, распределённость, отказоустойчивость обработки сообщений и интеграцию с внешней корпоративной системой при сохранении существующей модели безопасности на HTTP Basic + XML users.
