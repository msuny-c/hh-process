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

#let banner(body) = box(
  width: 100%,
  inset: 12pt,
  fill: rgb("#dff0df"),
  stroke: (paint: rgb("#b8d8b8"), thickness: 1pt),
  radius: 6pt,
)[
  #body
]

#titlepage(
  lab_no: 1,
  subject: "бизнес-логика программных систем",
  variant: "3212",
  student: "Григорий Садовой\nБайрамгулов Мунир",
  teacher: "Кривоносов Егор Дмитриевич",
)

= Условие задания
#v(4pt)
#banner[
  #strong[Вариант №3212:] hh.ru — работа, поиск персонала и публикация вакансий —
  #link("https://hh.ru/")[https://hh.ru/].
  Бизнес-процесс: обработка отзывов на вакансию.
]

Описать бизнес-процесс в соответствии с нотацией BPMN 2.0, после чего реализовать его в виде\ приложения на базе Spring Boot.

#strong[Порядок выполнения работы:]

+ Выбрать один из бизнес-процессов, реализуемых сайтом из варианта задания.
+ Утвердить выбранный бизнес-процесс у преподавателя.
+ Специфицировать модель реализуемого бизнес-процесса в соответствии с требованиями BPMN 2.0.
+ Разработать приложение на базе Spring Boot, реализующее описанный на предыдущем шаге бизнес-процесс. Приложение должно использовать СУБД PostgreSQL для хранения данных, для всех публичных интерфейсов должны быть разработаны REST API.
+ Разработать набор curl-скриптов, либо набор запросов для REST клиента Insomnia для тестирования публичных интерфейсов разработанного программного модуля. Запросы Insomnia оформить в виде файла экспорта.
+ Развернуть разработанное приложение на сервере #text(fill: rgb("#ff4fa3"))[helios].

#strong[Содержание отчёта:]

+ Текст задания.
+ Модель потока управления для автоматизируемого бизнес-процесса.
+ UML-диаграммы классов и пакетов разработанного приложения.
+ Спецификация REST API для всех публичных интерфейсов разработанного приложения.
+ Исходный код системы или ссылка на репозиторий с исходным кодом.
+ Выводы по работе.

= Модель BPMN
\
#image("HH.ru.png")



= Прецеденты

#let uc-table(title, actor, pre, main, post) = [
  #text(size: 9pt)[
    #table(
      columns: (1fr, 2.3fr),
      inset: 6pt,
      stroke: 0.6pt,
      align: left + top,
      [*Поле*], [*Описание*],
      [*Название*], [#title],
      [*Актор*], [#actor],
      [*Предусловие*], [#pre],
      [*Основное действие*], [#main],
      [*Постусловие*], [#post],
    )
  ]
]

== Регистрация кандидата
#uc-table(
  [Регистрация кандидата],
  [Кандидат],
  [Пользователь ещё не зарегистрирован.],
  [`POST /api/v1/auth/register/candidate`.],
  [В системе создан пользователь с ролью `CANDIDATE`, после чего он может авторизоваться.],
)

== Авторизация пользователя
#uc-table(
  [Авторизация пользователя],
  [Кандидат / Рекрутер / Администратор],
  [Пользователь существует в системе. Для кандидата при необходимости выполняется `POST /api/v1/auth/register/candidate`; для рекрутера и администратора используются seed-учётные записи.],
  [`POST /api/v1/auth/login`; `GET /api/v1/me`.],
  [Получены `access_token` и `refresh_token`, подтверждена роль текущего пользователя.],
)

== Создание вакансии
#uc-table(
  [Создание вакансии],
  [Рекрутер],
  [Рекрутер авторизован. Запросы: `POST /api/v1/auth/login`; `GET /api/v1/me`.],
  [`POST /api/v1/recruiters/vacancies`.],
  [Вакансия создана и доступна для дальнейшей работы с откликами.],
)

== Просмотр вакансий рекрутером
#uc-table(
  [Просмотр вакансий рекрутером],
  [Рекрутер],
  [Рекрутер авторизован. Запросы: `POST /api/v1/auth/login`; `GET /api/v1/me`.],
  [`GET /api/v1/recruiters/vacancies`.],
  [Получен список вакансий, созданных текущим рекрутером.],
)

== Отклик на вакансию
#uc-table(
  [Отклик на вакансию],
  [Кандидат],
  [Кандидат зарегистрирован и авторизован, в системе есть активная вакансия. Запросы: `POST /api/v1/auth/register/candidate`; `POST /api/v1/auth/login`; `GET /api/v1/me`; `POST /api/v1/recruiters/vacancies`.],
  [`POST /api/v1/candidates/vacancies/{vacancyId}`.],
  [Отклик создан, запускается автоскрининг, заявка появляется в списке откликов кандидата.],
)

== Просмотр кандидатом своих откликов
#uc-table(
  [Просмотр кандидатом своих откликов],
  [Кандидат],
  [Кандидат авторизован, ранее был создан хотя бы один отклик. Запросы: `POST /api/v1/auth/register/candidate`; `POST /api/v1/auth/login`; `POST /api/v1/candidates/vacancies/{vacancyId}`.],
  [`GET /api/v1/candidates/applications`; `GET /api/v1/candidates/applications/{id}`.],
  [Получен список откликов кандидата и детальная информация по выбранному отклику.],
)

== Просмотр рекрутером откликов
#uc-table(
  [Просмотр рекрутером откликов],
  [Рекрутер],
  [Рекрутер авторизован, по его вакансии уже есть отклик. Запросы: `POST /api/v1/auth/login`; `POST /api/v1/recruiters/vacancies`; `POST /api/v1/candidates/vacancies/{vacancyId}`.],
  [`GET /api/v1/recruiters/applications`; `GET /api/v1/recruiters/applications/{id}`.],
  [Получен список откликов по вакансиям рекрутера и детали выбранной заявки.],
)

== Отказ кандидату
#uc-table(
  [Отказ кандидату],
  [Рекрутер],
  [Рекрутер авторизован, существует отклик по его вакансии. Запросы: `POST /api/v1/auth/login`; `POST /api/v1/recruiters/vacancies`; `POST /api/v1/candidates/vacancies/{vacancyId}`; `GET /api/v1/recruiters/applications`.],
  [`POST /api/v1/recruiters/applications/{id}/reject`.],
  [Заявка отклонена, кандидату создано уведомление об отказе.],
)

== Приглашение кандидата
#uc-table(
  [Приглашение кандидата],
  [Рекрутер],
  [Рекрутер авторизован, существует отклик по его вакансии. Запросы: `POST /api/v1/auth/login`; `POST /api/v1/recruiters/vacancies`; `POST /api/v1/candidates/vacancies/{vacancyId}`; `GET /api/v1/recruiters/applications`.],
  [`POST /api/v1/recruiters/applications/{id}/invite`.],
  [Создано приглашение, кандидату отправлено уведомление, запускается ожидание ответа в течение 48 часов.],
)

== Ответ на приглашение
#uc-table(
  [Ответ на приглашение],
  [Кандидат],
  [Кандидат зарегистрирован и авторизован, рекрутер ранее отправил приглашение. Запросы: `POST /api/v1/auth/register/candidate`; `POST /api/v1/auth/login`; `POST /api/v1/recruiters/vacancies`; `POST /api/v1/candidates/vacancies/{vacancyId}`; `POST /api/v1/recruiters/applications/{id}/invite`.],
  [`POST /api/v1/candidates/applications/{id}/invitation-response`; `GET /api/v1/candidates/applications/{id}`.],
  [Ответ кандидата сохранён, статус заявки обновлён.],
)

== Просмотр уведомлений
#uc-table(
  [Просмотр уведомлений],
  [Кандидат / Рекрутер],
  [Пользователь авторизован, в системе уже есть хотя бы одно уведомление. Запросы: `POST /api/v1/auth/login`; один из бизнес-запросов, создающих уведомление, например `POST /api/v1/recruiters/applications/{id}/reject` или `POST /api/v1/recruiters/applications/{id}/invite`.],
  [`GET /api/v1/notifications`; `PATCH /api/v1/notifications/{id}/read`.],
  [Получен список уведомлений, выбранное уведомление отмечено как прочитанное.],
)

== Закрытие просроченных приглашений
#uc-table(
  [Закрытие просроченных приглашений],
  [Администратор],
  [Администратор авторизован, в системе есть приглашения с истёкшим сроком действия. Запросы: `POST /api/v1/auth/login`; предварительно должен быть выполнен сценарий `POST /api/v1/recruiters/applications/{id}/invite`.],
  [`POST /api/v1/admin/jobs/close-expired-invitations`.],
  [Просроченные приглашения закрыты, в ответе возвращается количество обработанных записей `closed_count`.],
)

= UML-диаграммы
\
#image("java.png")

= Исходный код
#v(4pt)
#let gh_button(url) = link(url)[
  #box(
    fill: rgb("#f6f8fa"),
    stroke: 1pt + rgb("#d0d7de"),
    inset: (x: 10pt, y: 6pt),
    radius: 999pt,
  )[
    #box(baseline: 0%)[
      #sicon(slug: "github", size: 0.9em, icon-color: "default")
    ]
    #h(0.4em)
    #text(size: 16pt)[#url]
  ]
]

#gh_button("https://github.com/msuny-c/hh-process")

= Вывод по работе
#v(4pt)
В ходе работы был выбран бизнес-процесс обработки откликов на вакансию (hh.ru) и специфицирован в нотации BPMN 2.0. Разработано приложение на Spring Boot, реализующее этот процесс. 

Реализованы основные элементы процесса: кандидат подаёт отклик с резюме и сопроводительным письмом; выполняется автоматический скрининг резюме по списку навыков вакансии с расчётом балла; заявки, прошедшие порог, попадают на рассмотрение рекрутеру; рекрутер может отклонить заявку с комментарием или отправить приглашение с текстом и сроком действия (48 часов); кандидат отвечает на приглашение (принятие или отказ); просроченные приглашения закрываются джобой. 

Набор запросов для проверки API оформлен в виде коллекции Postman. 
