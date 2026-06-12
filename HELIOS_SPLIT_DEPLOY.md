# Split Helios deployment: WildFly + Camunda + VPS PostgreSQL

This project is deployed without Docker on three machines:

1. **App Helios machine** — WildFly + `ROOT.war`.
2. **Camunda Helios machine** — standalone Camunda 7 Tomcat distribution.
3. **VPS** — PostgreSQL. The app connects to it by `POSTGRES_HOST` / `POSTGRES_PORT`. If `POSTGRES_HOST=localhost`, the workflow can optionally create a reverse SSH tunnel from the VPS to the app Helios host.

The deployment workflow is:

```text
.github/workflows/deploy-split-helios.yml
```

It builds the WAR, installs Camunda on the Camunda host, installs WildFly on the app host, uploads `ROOT.war`, creates `app.env`, starts both services and checks health endpoints.


## Branch and cleanup policy

This workflow is intentionally bound to the **`lab4`** branch.

```yaml
on:
  push:
    branches: ["lab4"]
```

It also has GitHub Actions concurrency enabled:

```yaml
concurrency:
  group: helios-split-lab4
  cancel-in-progress: true
```

So a new deploy cancels the previous still-running deploy job.

Before installing/restarting services, the workflow also stops old runtime tasks on the remote machines:

| Host | What is stopped before deploy |
|---|---|
| Camunda Helios | previous Tomcat/Camunda process, old Camunda pid, old Camunda-related SSH tunnels |
| App Helios | previous WildFly process, old `standalone.sh` / `jboss-modules`, old Camunda local tunnel, old reverse SSH tunnels, old deployment markers |

This is needed because Helios does not use Docker and old Java/SSH processes can keep ports busy after failed deployments.

## GitHub secrets and variables

Use existing repository secrets/variables where possible.

### App Helios host — WildFly

| Name | Type | Example | Meaning |
|---|---|---|---|
| `SSH_HOST` | variable or secret | `helios.cs.ifmo.ru` | App Helios host |
| `SSH_PORT` | variable or secret | `2222` | SSH port |
| `SSH_USER` | secret | `sXXXXXX` | App Helios user |
| `SSH_KEY` | secret | private key | Key for app Helios |
| `SERVER_PORT` | variable | `18081` | WildFly HTTP port; do not use `8080` |
| `WILDFLY_MANAGEMENT_HTTP_PORT` | variable | `19081` | WildFly management port |

### Camunda Helios host

The workflow uses the existing `WORKER_*` variables/secrets for the second Helios machine.

| Name | Type | Example | Meaning |
|---|---|---|---|
| `WORKER_SSH_HOST` | variable or secret | `helios.cs.ifmo.ru` | Camunda Helios host |
| `WORKER_SSH_PORT` | variable or secret | `2222` | SSH port |
| `WORKER_SSH_USER` | secret | `sYYYYYY` | Camunda Helios user |
| `WORKER_SSH_KEY` | secret | private key | Key for Camunda Helios |
| `CAMUNDA_PORT` | variable | `18082` | Camunda HTTP port. If absent, `WORKER_SERVER_PORT` is used. |
| `WORKER_SERVER_PORT` | variable | `18082` | Fallback Camunda port |
| `CAMUNDA_USERNAME` | secret/variable | `demo` | Camunda REST user |
| `CAMUNDA_PASSWORD` | secret/variable | `demo` | Camunda REST password |

### PostgreSQL VPS

| Name | Type | Example | Meaning |
|---|---|---|---|
| `POSTGRES_HOST` | variable | `localhost` | Host visible from the app Helios machine |
| `POSTGRES_PORT` | variable | `15432` | Port visible from the app Helios machine |
| `POSTGRES_DB` | secret | `postgres` | DB name |
| `POSTGRES_USER` | secret | `postgres` | DB user |
| `POSTGRES_PASSWORD` | secret | `...` | DB password |
| `POSTGRES_SCHEMA` | secret/variable | `public` | DB schema |

If PostgreSQL is already reachable from the app Helios machine by `POSTGRES_HOST:POSTGRES_PORT`, no tunnel is required.

If `POSTGRES_HOST=localhost` and the port must be opened through a reverse SSH tunnel from the VPS, add:

| Name | Type | Meaning |
|---|---|---|
| `VPS_SSH_HOST` | secret/variable | VPS host |
| `VPS_SSH_PORT` | secret/variable | VPS SSH port |
| `VPS_SSH_USER` | secret/variable | VPS user |
| `VPS_SSH_KEY` | secret | Key to SSH into VPS from GitHub Actions |
| `HELIOS_TUNNEL_PRIVATE_KEY` | secret | Key stored on VPS temporarily to open reverse SSH to Helios |
| `DB_TUNNEL_REMOTE_HOST` | secret/variable | DB host from VPS, usually `127.0.0.1` |
| `DB_TUNNEL_REMOTE_PORT` | secret/variable | DB port from VPS, usually `5432` |

The reverse tunnel is:

```text
VPS PostgreSQL ${DB_TUNNEL_REMOTE_HOST}:${DB_TUNNEL_REMOTE_PORT}
        ↓ ssh -R
App Helios 127.0.0.1:${POSTGRES_PORT}
```

## What is installed

### On the app Helios machine

Directory:

```text
~/apps/hh-process
~/apps/wildfly/wildfly-40.0.0.Final
```

Files:

```text
~/apps/hh-process/app.env
~/apps/hh-process/wildflyctl.sh
~/apps/hh-process/wildfly.log
~/apps/wildfly/wildfly-40.0.0.Final/standalone/deployments/ROOT.war
```

Manual commands:

```bash
~/apps/hh-process/wildflyctl.sh status
~/apps/hh-process/wildflyctl.sh restart
~/apps/hh-process/wildflyctl.sh log
```

Healthcheck from app Helios:

```bash
curl http://127.0.0.1:${SERVER_PORT}/actuator/health
```

### On the Camunda Helios machine

Directory:

```text
~/apps/camunda/camunda-bpm-tomcat-7.21.0
```

Files:

```text
~/apps/camunda/camundactl.sh
~/apps/camunda/camunda.log
```

Manual commands:

```bash
~/apps/camunda/camundactl.sh status
~/apps/camunda/camundactl.sh restart
~/apps/camunda/camundactl.sh log
```

Healthcheck from Camunda Helios:

```bash
curl http://127.0.0.1:${CAMUNDA_PORT}/engine-rest/version
```

## How the app reaches Camunda

The workflow creates an SSH local tunnel on the app Helios host:

```text
App Helios 127.0.0.1:${CAMUNDA_PORT}
        ↓ ssh -L
Camunda Helios 127.0.0.1:${CAMUNDA_PORT}
```

Therefore `app.env` contains:

```env
CAMUNDA_BASE_URL=http://127.0.0.1:${CAMUNDA_PORT}/engine-rest
```

## How to open services locally

App API through SSH tunnel:

```bash
ssh -L 18081:127.0.0.1:18081 -p <SSH_PORT> <SSH_USER>@<SSH_HOST>
open http://127.0.0.1:18081/actuator/health
```

Camunda webapps through SSH tunnel to the Camunda host:

```bash
ssh -L 18082:127.0.0.1:18082 -p <WORKER_SSH_PORT> <WORKER_SSH_USER>@<WORKER_SSH_HOST>
open http://127.0.0.1:18082/camunda/app/tasklist/default/
open http://127.0.0.1:18082/camunda/app/cockpit/default/
```

Default Camunda credentials are usually:

```text
demo / demo
```

The application also synchronizes app users/groups into Camunda during startup.

## Important notes

1. Do not use standard ports `8080`, `9990`, `5432` on Helios. Use repository variables such as `SERVER_PORT=18081`, `WORKER_SERVER_PORT=18082`, `POSTGRES_PORT=15432`.
2. WildFly 21 is too old for this Spring Boot 3 / Jakarta project. The workflow installs WildFly `40.0.0.Final`.
3. Camunda is not embedded into the app. It is a standalone Tomcat distribution on the second Helios machine.
4. PostgreSQL must allow prepared transactions for Narayana/JTA: `max_prepared_transactions > 0`.
5. If the workflow passes build but app healthcheck fails, download the `helios-wildfly-log-*` artifact.

## CI Docker tests before deploy

Workflow `.github/workflows/deploy-split-helios.yml` now has a separate `ci-docker-tests` job. It runs only on branch `lab4` and is required before deployment to Helios. The job does the following:

1. builds and starts the local Docker stack from `docker-compose.yml`:
   - PostgreSQL with `max_prepared_transactions=100`;
   - Camunda standalone container;
   - application container.
2. waits for:
   - `http://127.0.0.1:8080/actuator/health`;
   - `http://127.0.0.1:8081/engine-rest/version`.
3. builds the test container from `test/Dockerfile`.
4. runs all Python API/Camunda tests through `test/run_all.sh`.

Deploy jobs depend on this Docker test job, so deployment will not start if tests fail. Docker logs are uploaded as the `docker-ci-logs-*` artifact.

Before pushing to `lab4`, run the local Camunda model checks as well:

```bash
python3 test/test_camunda_visual_model_contract.py
python3 test/test_camunda_model_coverage.py
mvn test
```

`test_camunda_visual_model_contract.py` is the guard against broken diagrams on the defense machine: every BPMN must have a collaboration pool, at least one lane, named start/end events, BPMNShape for every flow node and BPMNEdge for every sequence flow. This is the check that prevents Camunda Modeler from showing `no diagram to display`.

After the Docker stack is up, run the runtime Camunda checks:

```bash
.venv/bin/python test/test_camunda_integration.py
.venv/bin/python test/test_camunda_decisions_runtime.py
.venv/bin/python test/test_camunda_tasklist_candidate_apply.py
.venv/bin/python test/test_camunda_smoke_flow.py
```

These checks verify deployed process definitions, deployed DMN decisions, Tasklist/Form candidate apply path, scheduler ownership by `hhTimeoutSchedulerProcess`, and absence of Camunda incidents.

Local equivalent:

```bash
docker compose down -v --remove-orphans
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
