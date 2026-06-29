# HH Process

![Java](https://img.shields.io/badge/Java-17-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-Migrations-CC0200?style=for-the-badge&logo=flyway&logoColor=white)
![Camunda](https://img.shields.io/badge/Camunda-BPM-FC5D0D?style=for-the-badge&logo=camunda&logoColor=white)
![WildFly](https://img.shields.io/badge/WildFly-Application%20Server-50A4D8?style=for-the-badge)
![Narayana](https://img.shields.io/badge/Narayana-JTA-6A5ACD?style=for-the-badge)
![Docker](https://img.shields.io/badge/Docker%20Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)

Проект показывает процесс обработки отклика на вакансию. Кандидат отправляет заявку, система проводит первичную проверку, рекрутер принимает решение, а дальнейшие действия идут через BPMN-процессы Camunda.

![Процесс отклика](report/HH.ru.png)

## Что внутри

- REST API для кандидатов, рекрутеров и администратора.
- Camunda BPM для пользовательских задач, форм и решений DMN.
- WildFly, в который разворачивается приложение.
- PostgreSQL для данных приложения и Camunda.
- Flyway-миграции, JTA/Narayana-транзакции и WebSocket-уведомления.

![BPMN-процесс заявки](report/application-process-bpmn.png)

## Как запустить

Нужны Docker и Docker Compose.

```bash
docker compose up --build
```

После запуска:

- приложение: `http://localhost:8080`
- Camunda: `http://localhost:8081`
- PostgreSQL: `localhost:5432`

Быстрая проверка:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/engine-rest/engine
```

Остановить проект:

```bash
docker compose down
```

Начать с чистой базы:

```bash
docker compose down -v
docker compose up --build
```
