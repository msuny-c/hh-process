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
  variant: "111",
  student: "Григорий Садовой\nБайрамгулов Мунир",
  teacher: "Кривоносов Егор Дмитриевич",
)

= Условие задания

Доработать приложение из лабораторной работы \#2, реализовав в нём асинхронное выполнение задач с распределением бизнес-логики между несколькими вычислительными узлами и выполнением периодических операций с использованием планировщика задач, а также интеграцию с внешней информационной системой.

#v(1em)

#text(weight: "bold")[Требования к реализации асинхронной обработки:]

+ Перед выполнением работы необходимо согласовать с преподавателем набор прецедентов, в реализации которых целесообразно использование асинхронного распределённого выполнения задач. Если таких прецедентов использования в имеющейся бизнес-процесса нет, нужно согласовать реализацию новых прецедентов, доработав таким образом модель бизнес-процесса из лабораторной работы \#1.
+ Асинхронное выполнение задач должно использовать модель доставки "подписка".
+ В качестве провайдера сервиса асинхронного обмена сообщениями необходимо использовать сервис подписки на базе Apache Kafka + ZooKeeper.
+ Для отправки сообщений необходимо использовать Kafka Producer API.
+ Для получения сообщений необходимо использовать клиент Kafka на базе Spring Boot.

#v(1em)

#text(weight: "bold")[Требования к реализации распределённой обработки:]

+ Обработка сообщений должна осуществляться на двух независимых друг от друга узлах сервера приложений.
+ Если логика сценария распределённой обработки предполагает транзакционность выполняемых операций, они должны быть включены в состав распределённой транзакции.

#v(1em)

#text(weight: "bold")[Требования к реализации запуска периодических задач по расписанию:]

+ Согласовать с преподавателем прецедент или прецеденты, в рамках которых выглядит целесообразным использовать планировщик задач. Если такие прецеденты отсутствуют -- согласовать с преподавателем новые и добавить их в модель автоматизируемого бизнес-процесса.
+ Реализовать утверждённые прецеденты с использованием планировщика задач Spring (\@Scheduled).

#v(1em)

#text(weight: "bold")[Требования к интеграции с внешней Корпоративной Информационной Системой (EIS):]

+ Корпоративная Информационная Система, с которой производится интеграция, а также её функциональные возможности выбираются на усмотрение преподавателя и согласуются с ним.
+ Взаимодействие с внешней Корпоративной Информационной Системой должно быть реализовано с помощью технологии JCA (Jakarta Connectors).

= Модель потока управления и бизнес-процесса

#image("HH.ru.png")

== Дорожки ответственности

#figure(
  image("diagrams/activity_screening.svg", width: 100%),
)

== Диаграмма развёртывания

#figure(
  image("diagrams/deployment_eis.svg", width: 100%),
)

== Диаграмма пакетов

#figure(
  image("diagrams/packages_hhprocess.svg", width: 100%),
)

= Спецификация публичных интерфейсов (REST и WebSocket)

== REST API

#figure(
  caption: [Публичные HTTP-интерфейсы приложения `hh-process` (роль `api`)],
  table(
    columns: (0.7fr, 2.2fr, 2.6fr),
    inset: 5pt,
    align: (left, left, left),
    stroke: 0.4pt + gray,
    table.header([*Метод*], [*Путь*], [*Назначение*]),
    [POST], [`/api/v1/auth/register/candidate`], [Регистрация кандидата (доступ настраивается в `SecurityConfig`).],
    [GET], [`/api/v1/me`], [Профиль текущего пользователя.],
    [POST], [`/api/v1/candidates/vacancies/\{vacancyId\}`], [Подать заявку на вакансию; ответ: `APPLICATION_SUBMITTED`.],
    [GET], [`/api/v1/candidates/applications`], [Список своих заявок.],
    [GET], [`/api/v1/candidates/applications/\{applicationId\}`], [Заявка по id.],
    [POST], [`/api/v1/candidates/applications/\{applicationId\}/invitation-response`], [Ответ на приглашение.],
    [POST], [`/api/v1/recruiters/vacancies`], [Создать вакансию.],
    [GET], [`/api/v1/recruiters/vacancies`], [Свои вакансии.],
    [PATCH], [`/api/v1/recruiters/vacancies/\{vacancyId\}/status`], [Изменить статус вакансии.],
    [POST], [`/api/v1/recruiters/vacancies/\{vacancyId\}/close`], [Закрыть вакансию (составная логика).],
    [GET], [`/api/v1/recruiters/applications`], [Заявки по вакансиям рекрутера (фильтры query).],
    [GET], [`/api/v1/recruiters/applications/\{applicationId\}`], [Заявка рекрутера по id.],
    [POST], [`/api/v1/recruiters/applications/\{applicationId\}/reject`], [Отклонить заявку.],
    [POST], [`/api/v1/recruiters/applications/\{applicationId\}/invite`], [Пригласить на интервью (JTA).],
    [POST], [`/api/v1/recruiters/interviews/\{interviewId\}/cancel`], [Отменить интервью.],
    [GET], [`/api/v1/recruiters/schedule`], [Недельное расписание рекрутера (`weekOffset`).],
    [GET], [`/api/v1/notifications`], [Список уведомлений пользователя.],
    [PATCH], [`/api/v1/notifications/\{notificationId\}/read`], [Отметить прочитанным.],
    [POST], [`/api/v1/admin/jobs/close-expired-invitations`], [Принудительно закрыть просроченные приглашения.],
    [POST], [`/api/v1/admin/debug/schedule-failure/\{enabled\}`], [Debug: ошибка резерва слота (таблица `recruiter_schedule_slots`).],
  ),
)

== WebSocket (STOMP)

- Точка подключения: `/ws` (с поддержкой SockJS и без).
- Брокер: префиксы `/topic`, `/queue`; приложение — `/app`; пользовательские каналы — `/user`.
- Уведомления доставляются на `/user/queue/notifications` (см. `WebSocketNotificationService`).

== Модуль Odoo `hh_process_eis` (HTTP EIS)

#figure(
  caption: [JSON REST, реализованный в `odoo-addons/hh_process_eis`],
  table(
    columns: (0.8fr, 2.4fr, 2.3fr),
    inset: 5pt,
    align: left,
    stroke: 0.4pt + gray,
    table.header([*Метод*], [*Путь*], [*Назначение*]),
    [POST], [`/api/v1/calendar/entries`], [Создать запись календаря (экспорт интервью).],
    [POST], [`/api/v1/calendar/entries/\{interviewId\}/cancel`], [Отмена записи.],
    [GET], [`/api/v1/calendar/entries/\{interviewId\}`], [Получить запись.],
    [GET], [`/actuator/health`], [Health (для оркестрации).],
  ),
)

= Плановые задачи и экспорт в EIS

Через Spring `@Scheduled` на узле `api` реализован только `TimeoutService` (закрытие просроченных приглашений). Экспорт интервью в EIS — **синхронно** в запросе кандидата (ACCEPT): `InvitationResponseService` → `InterviewExportRequestService` → JCA-HTTP.
