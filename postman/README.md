# Legacy REST collection

Эта папка оставлена только как архив старой REST-коллекции для ручной отладки служебного API.

Для защиты лабораторной её использовать не нужно: пользовательский сценарий перенесён в Camunda Tasklist и Camunda Forms, а проверка бизнес-правил выполняется через BPMN/DMN и автотесты.

Актуальные инструкции:

- `CAMUNDA_README.md` — полный сценарий защиты, роли, Cockpit, Tasklist, DMN, транзакции и scheduler.
- `docs/CAMUNDA_TASKLIST_DEMO.md` — короткий Tasklist-only сценарий.
- `README.md` — Docker-запуск, полный список Python/Camunda тестов и команды для прогона тестов внутри Docker.
- `test/test_camunda_visual_model_contract.py` — проверка, что все BPMN открываются как диаграммы с pool/lane и подписанными start/end.
- `test/test_camunda_tasklist_candidate_apply.py` — проверка, что кандидат создаёт отклик через Camunda Form path.
