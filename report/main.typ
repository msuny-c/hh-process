




  width: 100%,
  inset: 12pt,
  fill: rgb("
  stroke: (paint: rgb("
  radius: 6pt,
)[
]

  width: 100%,
  inset: 12pt,
  fill: rgb("
  stroke: (paint: rgb("
  radius: 6pt,
)[
]

  lab_no: 2,
  subject: "бизнес-логика программных систем",
  variant: "3212",
  student: "Григорий Садовой\nБайрамгулов Мунир",
  teacher: "Кривоносов Егор Дмитриевич",
)

= Условие задания
  Бизнес-процесс: обработка отзывов на вакансию.
]

Доработать приложение из лабораторной работы №1, реализовав в нём управление транзакциями и разграничение доступа к операциям бизнес-логики в соответствии с заданной политикой доступа.


+ Переработать согласованные с преподавателем прецеденты (или по согласованию с ним разработать новые), объединив взаимозависимые операции в рамках транзакций.
+ Управление транзакциями необходимо реализовать с помощью Spring JTA.
+ В реализованных (или модифицированных) прецедентах необходимо использовать декларативное управление транзакциями.
+ В качестве менеджера транзакций необходимо использовать Narayana.


+ Разработать, специфицировать и согласовать с преподавателем набор привилегий, в соответствии с которыми будет разграничиваться доступ к операциям.
+ Специфицировать и согласовать с преподавателем набор ролей, осуществляющих доступ к операциям бизнес-логики приложения.
+ Реализовать разработанную модель разграничений доступа к операциям бизнес-логики на базе Spring Security. Информацию об учётных записях пользователей необходимо сохранять в файле XML, для аутентификации использовать HTTP basic.


+ Текст задания.
+ Модель потока управления для автоматизируемого бизнес-процесса.
+ Спецификация пользовательских привилегий и ролей, реализованных в приложении.
+ UML-диаграммы классов и пакетов разработанного приложения.
+ Спецификация REST API для всех публичных интерфейсов разработанного приложения.
+ Исходный код системы или ссылка на репозиторий с исходным кодом.
+ Выводы по работе.

= Модель BPMN
\

= Прецеденты

      columns: (1fr, 2.3fr),
      inset: 6pt,
      stroke: 0.6pt,
      align: left + top,
      [*Поле*], [*Описание*],
      [*Название*], [
      [*Актор*], [
      [*Предусловие*], [
      [*Основное действие*], [
      [*Постусловие*], [
    )
  ]
]

== Регистрация кандидата
  [Регистрация кандидата],
  [Кандидат],
  [Пользователь ещё не зарегистрирован.],
  [`POST /api/v1/auth/register/candidate`.],
  [В системе создан пользователь с ролью `CANDIDATE`, после чего он может авторизоваться.],
)

== Просмотр профиля
  [Просмотр профиля],
  [Кандидат / Рекрутер / Администратор],
  [Пользователь авторизован.],
  [`GET /api/v1/me`.],
  [Возвращены данные текущего пользователя: идентификатор, e-mail и роль.],
)

== Создание вакансии
  [Создание вакансии],
  [Рекрутер],
  [Рекрутер авторизован.],
  [`POST /api/v1/recruiters/vacancies`.],
  [Вакансия создана и доступна для подачи откликов.],
)

== Просмотр вакансий рекрутером
  [Просмотр вакансий рекрутером],
  [Рекрутер],
  [Рекрутер авторизован.],
  [`GET /api/v1/recruiters/vacancies`.],
  [Получен список вакансий, созданных текущим рекрутером.],
)

== Изменение статуса вакансии
  [Изменение статуса вакансии],
  [Рекрутер],
  [Рекрутер авторизован; вакансия принадлежит ему.],
  [`PATCH /api/v1/recruiters/vacancies/{vacancyId}/status`.],
  [Статус вакансии обновлён.],
)

== Закрытие вакансии (составная транзакция)
  [Закрытие вакансии],
  [Рекрутер],
  [Рекрутер авторизован; вакансия активна и принадлежит ему.],
  [`POST /api/v1/recruiters/vacancies/{vacancyId}/close`.],
  [Вакансия закрыта, все активные заявки по ней отклонены в рамках одной JTA-транзакции.],
)

== Отклик на вакансию
  [Отклик на вакансию],
  [Кандидат],
  [Кандидат авторизован; в системе есть активная вакансия.],
  [`POST /api/v1/candidates/vacancies/{vacancyId}`.],
  [Отклик создан; запускается автоматический скрининг; заявка появляется в списке откликов кандидата.],
)

== Просмотр кандидатом своих откликов
  [Просмотр кандидатом своих откликов],
  [Кандидат],
  [Кандидат авторизован; ранее создан хотя бы один отклик.],
  [`GET /api/v1/candidates/applications`; `GET /api/v1/candidates/applications/{id}`.],
  [Получен список откликов кандидата и детальная информация по выбранному отклику.],
)

== Ответ на приглашение
  [Ответ на приглашение],
  [Кандидат],
  [Кандидат авторизован; рекрутер отправил приглашение по его заявке.],
  [`POST /api/v1/candidates/applications/{id}/invitation-response`.],
  [Ответ кандидата сохранён; статус заявки обновлён; при принятии создаётся интервью.],
)

== Просмотр рекрутером откликов
  [Просмотр рекрутером откликов],
  [Рекрутер],
  [Рекрутер авторизован; по его вакансиям есть отклики.],
  [`GET /api/v1/recruiters/applications`; `GET /api/v1/recruiters/applications/{id}`.],
  [Получен список откликов по вакансиям рекрутера и детали выбранной заявки.],
)

== Отказ кандидату
  [Отказ кандидату],
  [Рекрутер],
  [Рекрутер авторизован; существует активный отклик по его вакансии.],
  [`POST /api/v1/recruiters/applications/{id}/reject`.],
  [Заявка отклонена; кандидату создано уведомление об отказе.],
)

== Приглашение кандидата на интервью
  [Приглашение кандидата на интервью],
  [Рекрутер],
  [Рекрутер авторизован; существует активный отклик по его вакансии.],
  [`POST /api/v1/recruiters/applications/{id}/invite`.],
  [Создано приглашение; кандидату отправлено уведомление; запускается ожидание ответа.],
)

== Отмена интервью
  [Отмена интервью],
  [Рекрутер],
  [Рекрутер авторизован; существует назначенное интервью.],
  [`POST /api/v1/recruiters/interviews/{interviewId}/cancel`.],
  [Интервью отменено; кандидату создано уведомление.],
)

== Просмотр расписания рекрутера
  [Просмотр расписания рекрутера],
  [Рекрутер],
  [Рекрутер авторизован.],
  [`GET /api/v1/recruiters/schedule?weekOffset=0`.],
  [Возвращено расписание интервью рекрутера на указанную неделю.],
)

== Просмотр уведомлений
  [Просмотр уведомлений],
  [Кандидат / Рекрутер],
  [Пользователь авторизован; в системе есть хотя бы одно уведомление.],
  [`GET /api/v1/notifications`; `PATCH /api/v1/notifications/{id}/read`.],
  [Получен список уведомлений; выбранное уведомление отмечено как прочитанное.],
)

== Закрытие просроченных приглашений
  [Закрытие просроченных приглашений],
  [Администратор],
  [Администратор авторизован; в системе есть приглашения с истёкшим сроком.],
  [`POST /api/v1/admin/jobs/close-expired-invitations`.],
  [Просроченные приглашения закрыты; в ответе возвращается количество обработанных записей `closed_count`.],
)

= Спецификация привилегий и ролей

== Привилегии

    columns: (auto, 1fr),
    inset: 6pt,
    stroke: 0.6pt,
    align: left + top,
    [*Привилегия*], [*Описание*],
    [`PROFILE_VIEW`], [Просмотр профиля текущего пользователя (`GET /api/v1/me`)],
    [`NOTIFICATION_VIEW`], [Просмотр уведомлений (`GET /api/v1/notifications`)],
    [`NOTIFICATION_MARK_READ`], [Отметка уведомления как прочитанного (`PATCH /api/v1/notifications/{id}/read`)],
    [`APPLICATION_CREATE`], [Подача заявки на вакансию (`POST /api/v1/candidates/vacancies/{vacancyId}`)],
    [`APPLICATION_VIEW_OWN`], [Просмотр собственных заявок (`GET /api/v1/candidates/applications`)],
    [`APPLICATION_RESPOND_INVITATION_OWN`], [Ответ на приглашение по своей заявке (`POST /api/v1/candidates/applications/{id}/invitation-response`)],
    [`VACANCY_CREATE`], [Создание вакансии (`POST /api/v1/recruiters/vacancies`)],
    [`VACANCY_VIEW_OWN`], [Просмотр собственных вакансий (`GET /api/v1/recruiters/vacancies`)],
    [`VACANCY_UPDATE_OWN`], [Изменение статуса / закрытие собственной вакансии],
    [`APPLICATION_VIEW_ASSIGNED`], [Просмотр заявок по своим вакансиям (`GET /api/v1/recruiters/applications`)],
    [`APPLICATION_REJECT_ASSIGNED`], [Отклонение заявки / отмена интервью по своим вакансиям],
    [`APPLICATION_INVITE_ASSIGNED`], [Приглашение кандидата на интервью по своим вакансиям],
    [`SCHEDULE_VIEW_OWN`], [Просмотр расписания интервью (`GET /api/v1/recruiters/schedule`)],
    [`JOB_RUN_TIMEOUT_CLOSE`], [Запуск джобы закрытия просроченных приглашений (`POST /api/v1/admin/jobs/close-expired-invitations`)],
  )
]

== Роли и их привилегии

    columns: (auto, 1fr),
    inset: 6pt,
    stroke: 0.6pt,
    align: left + top,
    [*Роль*], [*Привилегии*],
    [`CANDIDATE`], [
      `PROFILE_VIEW`,
      `NOTIFICATION_VIEW`,
      `NOTIFICATION_MARK_READ`,
      `APPLICATION_CREATE`,
      `APPLICATION_VIEW_OWN`,
      `APPLICATION_RESPOND_INVITATION_OWN`
    ],
    [`RECRUITER`], [
      `PROFILE_VIEW`,
      `NOTIFICATION_VIEW`,
      `NOTIFICATION_MARK_READ`,
      `VACANCY_CREATE`,
      `VACANCY_VIEW_OWN`,
      `VACANCY_UPDATE_OWN`,
      `APPLICATION_VIEW_ASSIGNED`,
      `APPLICATION_REJECT_ASSIGNED`,
      `APPLICATION_INVITE_ASSIGNED`,
      `SCHEDULE_VIEW_OWN`
    ],
    [`ADMIN`], [
      `PROFILE_VIEW`,
      `JOB_RUN_TIMEOUT_CLOSE`
    ],
  )
]

== Хранение учётных записей

Данные учётных записей пользователей хранятся в XML-файле, путь к которому задаётся переменной окружения `APP_SECURITY_USERS_XML`. Аутентификация осуществляется по схеме HTTP Basic. Пример структуры файла:

```xml
<users>
  <user email="recruiter@example.com" passwordHash="$2a$10$..." />
  <user email="admin@example.com"     passwordHash="$2a$10$..." />
</users>
```


= UML-диаграммы


= REST API

      columns: (auto, 1fr),
      inset: 5pt,
      stroke: 0.6pt,
      align: left + top,
      [*Метод*], [
      [*Путь*], [
      [*Привилегия*], [
      [*Описание*], [
      ..if body != none { ([*Тело запроса*], body) },
      ..if resp != none { ([*Ответ*], resp) },
    )
  ]
]

== Аутентификация и профиль

  "POST", "/api/v1/auth/register/candidate",
  "— (публичный)",
  [Регистрация нового кандидата.],
  body: [`{ "email": "...", "password": "...", "firstName": "...", "lastName": "..." }`],
  resp: [`201 Created` — `{ "id": "uuid", "email": "..." }`],
)

  "GET", "/api/v1/me",
  "PROFILE_VIEW",
  [Получить профиль текущего авторизованного пользователя.],
  resp: [`200 OK` — `{ "id": "uuid", "email": "...", "role": "CANDIDATE" }`],
)

== Вакансии (рекрутер)

  "POST", "/api/v1/recruiters/vacancies",
  "VACANCY_CREATE",
  [Создать новую вакансию.],
  body: [`{ "title": "...", "description": "...", "requiredSkills": ["Java", "Spring"] }`],
  resp: [`201 Created` — объект вакансии `VacancyResponse`],
)

  "GET", "/api/v1/recruiters/vacancies",
  "VACANCY_VIEW_OWN",
  [Получить список вакансий текущего рекрутера.],
  resp: [`200 OK` — массив `VacancyResponse`],
)

  "PATCH", "/api/v1/recruiters/vacancies/{vacancyId}/status",
  "VACANCY_UPDATE_OWN",
  [Изменить статус вакансии (кроме `CLOSED`; для закрытия используется отдельный эндпоинт).],
  body: [`{ "status": "ACTIVE" }`],
  resp: [`200 OK` — обновлённый `VacancyResponse`],
)

  "POST", "/api/v1/recruiters/vacancies/{vacancyId}/close",
  "VACANCY_UPDATE_OWN",
  [Закрыть вакансию как составную JTA-транзакцию: статус вакансии меняется на `CLOSED`, все активные заявки отклоняются атомарно.],
  body: [`{ "reason": "..." }`],
  resp: [`200 OK` — обновлённый `VacancyResponse`],
)

== Заявки (кандидат)

  "POST", "/api/v1/candidates/vacancies/{vacancyId}",
  "APPLICATION_CREATE",
  [Подать отклик на вакансию.],
  body: [`{ "resumeText": "...", "coverLetter": "..." }`],
  resp: [`201 Created` — `CreateApplicationResponse` (id, статус, скрининг-балл)],
)

  "GET", "/api/v1/candidates/applications",
  "APPLICATION_VIEW_OWN",
  [Получить список собственных откликов.],
  resp: [`200 OK` — массив `CandidateApplicationResponse`],
)

  "GET", "/api/v1/candidates/applications/{applicationId}",
  "APPLICATION_VIEW_OWN",
  [Получить детальную информацию по отклику.],
  resp: [`200 OK` — `CandidateApplicationResponse`],
)

  "POST", "/api/v1/candidates/applications/{applicationId}/invitation-response",
  "APPLICATION_RESPOND_INVITATION_OWN",
  [Ответить на приглашение рекрутера (принять или отклонить).],
  body: [`{ "accepted": true }`],
  resp: [`200 OK` — `InvitationResponseResponse`],
)

== Заявки (рекрутер)

  "GET", "/api/v1/recruiters/applications",
  "APPLICATION_VIEW_ASSIGNED",
  [Получить заявки по своим вакансиям. Фильтрация по `status` и `vacancy_id` (query-параметры).],
  resp: [`200 OK` — массив `RecruiterApplicationResponse`],
)

  "GET", "/api/v1/recruiters/applications/{applicationId}",
  "APPLICATION_VIEW_ASSIGNED",
  [Получить детальную информацию по заявке.],
  resp: [`200 OK` — `RecruiterApplicationResponse`],
)

  "POST", "/api/v1/recruiters/applications/{applicationId}/reject",
  "APPLICATION_REJECT_ASSIGNED",
  [Отклонить заявку кандидата с указанием причины.],
  body: [`{ "reason": "..." }`],
  resp: [`200 OK` — `RejectResponse`],
)

  "POST", "/api/v1/recruiters/applications/{applicationId}/invite",
  "APPLICATION_INVITE_ASSIGNED",
  [Пригласить кандидата на интервью.],
  body: [`{ "message": "...", "scheduledAt": "2025-05-01T10:00:00" }`],
  resp: [`200 OK` — `InviteResponse`],
)

== Интервью (рекрутер)

  "POST", "/api/v1/recruiters/interviews/{interviewId}/cancel",
  "APPLICATION_REJECT_ASSIGNED",
  [Отменить запланированное интервью.],
  body: [`{ "reason": "..." }`],
  resp: [`200 OK` — `InterviewActionResponse`],
)

== Расписание (рекрутер)

  "GET", "/api/v1/recruiters/schedule",
  "SCHEDULE_VIEW_OWN",
  [Получить расписание интервью на указанную неделю. Параметр `weekOffset` — смещение в неделях от текущей (диапазон: −52 … +52, по умолчанию 0).],
  resp: [`200 OK` — `WeekScheduleResponse`],
)

== Уведомления

  "GET", "/api/v1/notifications",
  "NOTIFICATION_VIEW",
  [Получить список уведомлений текущего пользователя.],
  resp: [`200 OK` — массив `NotificationResponse`],
)

  "PATCH", "/api/v1/notifications/{notificationId}/read",
  "NOTIFICATION_MARK_READ",
  [Отметить уведомление как прочитанное.],
  resp: [`204 No Content`],
)

== Административные джобы

  "POST", "/api/v1/admin/jobs/close-expired-invitations",
  "JOB_RUN_TIMEOUT_CLOSE",
  [Закрыть все приглашения с истёкшим сроком действия.],
  resp: [`200 OK` — `{ "closed_count": 3 }`],
)

= Исходный код
    fill: rgb("
    stroke: 1pt + rgb("
    inset: (x: 10pt, y: 6pt),
    radius: 999pt,
  )[
    ]
  ]
]


= Вывод по работе
В ходе второй лабораторной работы приложение hh.ru (обработка откликов на вакансию) было доработано в части управления транзакциями и разграничения доступа.

*Управление транзакциями.* Взаимозависимые операции бизнес-процесса объединены в JTA-транзакции с декларативным управлением через аннотацию `@Transactional`. В качестве менеджера транзакций подключён Narayana (Spring JTA).

*Разграничение доступа.* Разработана модель из 14 привилегий, распределённых по трём ролям (`CANDIDATE`, `RECRUITER`, `ADMIN`). Аутентификация выполняется по схеме HTTP Basic; учётные записи хранятся в XML-файле.

В результате приложение получило надёжный механизм атомарных операций и строгую политику доступа, соответствующую требованиям задания.
