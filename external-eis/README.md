# external-eis

Минимальный HTTP-сервис (Spring Boot), имитирующий внешнюю EIS-календарь. Основное приложение обращается к нему через JCA (`CalendarManagedConnectionFactory` + `APP_EIS_REMOTE_BASE_URL`), без Kafka.

## API

- `POST /api/v1/calendar/entries` — создать запись (тело: `interviewId`, `scheduledAt`, `durationMinutes`, `candidateId`, `recruiterId`).
- `POST /api/v1/calendar/entries/{interviewId}/cancel`
- `GET /api/v1/calendar/entries/{interviewId}`

## Сборка и запуск

```bash
mvn -f external-eis/pom.xml package
java -jar external-eis/target/external-eis-0.0.1-SNAPSHOT.jar
```

По умолчанию порт **8090**. Деплой на сервер: см. `.github/workflows/deploy.yml` (артефакт + `infra/eisctl.sh` в `~/apps/blps-eis`).
