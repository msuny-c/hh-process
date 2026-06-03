#!/usr/bin/env sh
set -eu

: "${BASE_URL:=http://127.0.0.1:8080}"
: "${CAMUNDA_URL:=http://127.0.0.1:8081/engine-rest}"
: "${POSTGRES_HOST:=127.0.0.1}"
: "${POSTGRES_PORT:=5432}"
: "${POSTGRES_DB:=postgres}"
: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_PASSWORD:=postgres}"
: "${POSTGRES_SCHEMA:=public}"

export BASE_URL CAMUNDA_URL POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_SCHEMA

echo "API tests BASE_URL=${BASE_URL}"
echo "Camunda tests CAMUNDA_URL=${CAMUNDA_URL}"
echo "DB tests POSTGRES=${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}?schema=${POSTGRES_SCHEMA}"

python /tests/test.py
python /tests/test_composite_transactions.py
python /tests/test_security_validation.py
python /tests/test_access_matrix.py
python /tests/test_transaction_atomicity.py
python /tests/test_business_rules.py
python /tests/test_timeout_job_db_fixture.py
python /tests/test_admin_interview_reset.py
python /tests/test_camunda_integration.py
python /tests/test_camunda_e2e.py

echo "All Docker API/Camunda tests passed."
