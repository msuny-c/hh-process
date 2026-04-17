#!/usr/bin/env python3
"""
Build app.env for a screening worker on a separate host.

Requires env:
  WORKER_NUM — "1" or "2"
  STACK_HOST — hostname or IP reachable from the worker (PostgreSQL + Kafka bootstrap)
  ALL_SECRETS, ALL_VARS — JSON from GitHub Actions (same idea as deploy job)

Optional:
  STACK_KAFKA_PORT (default 9092) — Kafka listener on STACK_HOST (native KRaft / same as install-kafka-kraft.sh)
  STACK_POSTGRES_PORT — if set, overrides POSTGRES_PORT for the worker

Worker-specific instance names:
  WORKER1_INSTANCE_NAME / WORKER2_INSTANCE_NAME from vars, else hh-worker-1 / hh-worker-2
"""
from __future__ import annotations

import json
import os
import sys

EXCLUDED_KEYS = frozenset(
    {
        "SSH_KEY",
        "SSH_HOST",
        "SSH_PORT",
        "SSH_USER",
        "VPS_SSH_KEY",
        "VPS_SSH_HOST",
        "VPS_SSH_PORT",
        "VPS_SSH_USER",
        "HELIOS_TUNNEL_PRIVATE_KEY",
        "DB_TUNNEL_REMOTE_HOST",
        "DB_TUNNEL_REMOTE_PORT",
        "github_token",
        "WORKER1_SSH_KEY",
        "WORKER1_SSH_HOST",
        "WORKER1_SSH_PORT",
        "WORKER1_SSH_USER",
        "WORKER2_SSH_KEY",
        "WORKER2_SSH_HOST",
        "WORKER2_SSH_PORT",
        "WORKER2_SSH_USER",
    }
)


def main() -> None:
    worker_num = os.environ.get("WORKER_NUM", "").strip()
    if worker_num not in ("1", "2"):
        print("WORKER_NUM must be 1 or 2", file=sys.stderr)
        sys.exit(1)

    stack_host = os.environ.get("STACK_HOST", "").strip()
    if not stack_host:
        print("STACK_HOST is required", file=sys.stderr)
        sys.exit(1)

    kafka_port = os.environ.get("STACK_KAFKA_PORT", "9092").strip() or "9092"
    secrets = json.loads(os.environ.get("ALL_SECRETS", "{}"))
    vars_ = json.loads(os.environ.get("ALL_VARS", "{}"))

    merged: dict[str, str] = {}
    for key, value in {**secrets, **vars_}.items():
        if key in EXCLUDED_KEYS:
            continue
        if value is None:
            continue
        merged[key] = str(value).strip()

    instance_key = f"WORKER{worker_num}_INSTANCE_NAME"
    narayana_key = f"WORKER{worker_num}_NARAYANA_NODE_IDENTIFIER"
    instance = merged.get(instance_key) or os.environ.get(instance_key) or f"hh-worker-{worker_num}"
    narayana = merged.get(narayana_key) or os.environ.get(narayana_key) or instance

    stack_pg_port = os.environ.get("STACK_POSTGRES_PORT", "").strip()
    if stack_pg_port:
        merged["POSTGRES_PORT"] = stack_pg_port

    merged["POSTGRES_HOST"] = stack_host
    merged["KAFKA_BOOTSTRAP_SERVERS"] = f"{stack_host}:{kafka_port}"
    merged["APP_ROLE"] = "worker"
    merged["APP_INSTANCE_NAME"] = instance
    merged["NARAYANA_NODE_IDENTIFIER"] = narayana
    merged["KAFKA_GROUP_ID"] = "hh-process-screening"
    merged["APP_SCREENING_ENABLED"] = "true"
    merged["APP_NOTIFICATIONS_ENABLED"] = "false"
    merged["APP_EIS_ENABLED"] = "false"

    out_path = os.environ.get("OUT_PATH", f"worker{worker_num}.env")
    with open(out_path, "w") as f:
        for key in sorted(merged.keys()):
            f.write(f"{key}={merged[key]}\n")

    print(f"Wrote {out_path} with {len(merged)} variables")


if __name__ == "__main__":
    main()
