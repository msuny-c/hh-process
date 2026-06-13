#!/usr/bin/env sh
set -eu

CAMUNDA_HTTP_PORT="${CAMUNDA_HTTP_PORT:-18082}"
CAMUNDA_URL="${CAMUNDA_URL:-http://127.0.0.1:${CAMUNDA_HTTP_PORT}/engine-rest}"
PYTHON_BIN="${PYTHON:-}"

if [ -z "$PYTHON_BIN" ]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN=python3
  else
    PYTHON_BIN=python
  fi
fi

"$PYTHON_BIN" - "$CAMUNDA_URL" <<'PY'
import base64
import json
import os
import sys
import socket
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen

base = sys.argv[1].rstrip("/")
username = os.getenv("CAMUNDA_USERNAME", "")
password = os.getenv("CAMUNDA_PASSWORD", "")

def request(method, path, body=None, ok=(200, 204)):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if username or password:
        token = base64.b64encode(f"{username}:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    req = Request(base + path, data=data, headers=headers, method=method)
    last = None
    for attempt in range(1, 4):
        try:
            with urlopen(req, timeout=30) as response:
                raw = response.read().decode("utf-8")
                if response.status not in ok:
                    raise RuntimeError(f"{method} {path} -> {response.status}: {raw}")
                return json.loads(raw) if raw else None
        except HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            if exc.code in ok:
                return json.loads(raw) if raw else None
            raise RuntimeError(f"{method} {path} -> {exc.code}: {raw}") from exc
        except (TimeoutError, socket.timeout, URLError) as exc:
            last = exc
            if attempt == 3:
                break
            time.sleep(2 * attempt)
    raise RuntimeError(f"{method} {path} failed after retries: {last}")

deadline = time.time() + int(os.getenv("CAMUNDA_BOOTSTRAP_TIMEOUT_SEC", "180"))
while True:
    try:
        request("GET", "/version")
        break
    except (RuntimeError, URLError):
        if time.time() > deadline:
            raise
        time.sleep(2)

def ensure_group(group_id, name, group_type="WORKFLOW"):
    groups = request("GET", "/group?" + urlencode({"id": group_id})) or []
    if not groups:
        request("POST", "/group/create", {"id": group_id, "name": name, "type": group_type})
        print(f"created group {group_id}")

def ensure_user(user_id, first_name, last_name, email, user_password):
    profile = {"id": user_id, "firstName": first_name, "lastName": last_name, "email": email}
    try:
        request("GET", f"/user/{quote(user_id)}/profile")
        request("PUT", f"/user/{quote(user_id)}/profile", profile)
        request("PUT", f"/user/{quote(user_id)}/credentials", {"password": user_password})
        print(f"updated user {user_id}")
    except RuntimeError as exc:
        if "-> 404:" not in str(exc):
            raise
        request("POST", "/user/create", {"profile": profile, "credentials": {"password": user_password}})
        print(f"created user {user_id}")

def ensure_membership(group_id, user_id):
    groups = request("GET", "/group?" + urlencode({"member": user_id, "id": group_id})) or []
    if not groups:
        request("PUT", f"/group/{quote(group_id)}/members/{quote(user_id)}")
        print(f"added {user_id} to {group_id}")

def ensure_authorization(group_id, resource_type, resource_id, permissions):
    query = urlencode({
        "type": 1,
        "groupIdIn": group_id,
        "resourceType": resource_type,
        "resourceId": resource_id,
    })
    existing = request("GET", f"/authorization?{query}") or []
    for item in existing:
        existing_permissions = set(item.get("permissions") or [])
        if existing_permissions.issuperset(set(permissions)):
            return
    if existing:
        authorization_id = existing[0].get("id")
        if authorization_id:
            request("PUT", f"/authorization/{quote(authorization_id)}", {
                "type": 1,
                "groupId": group_id,
                "resourceType": resource_type,
                "resourceId": resource_id,
                "permissions": permissions,
            })
            print(f"updated authorization {group_id} resourceType={resource_type} resourceId={resource_id} permissions={','.join(permissions)}")
        return
    request("POST", "/authorization/create", {
        "type": 1,
        "groupId": group_id,
        "resourceType": resource_type,
        "resourceId": resource_id,
        "permissions": permissions,
    })
    print(f"authorized {group_id} resourceType={resource_type} resourceId={resource_id} permissions={','.join(permissions)}")

def remove_authorization(group_id, resource_type, resource_id):
    query = urlencode({
        "type": 1,
        "groupIdIn": group_id,
        "resourceType": resource_type,
        "resourceId": resource_id,
    })
    existing = request("GET", f"/authorization?{query}") or []
    for item in existing:
        authorization_id = item.get("id")
        if authorization_id:
            request("DELETE", f"/authorization/{quote(authorization_id)}")
            print(f"removed authorization {group_id} resourceType={resource_type} resourceId={resource_id}")

for group_id, name, group_type in (
    ("ADMIN", "Administrators", "WORKFLOW"),
    ("RECRUITER", "Recruiters", "WORKFLOW"),
    ("CANDIDATE", "Candidates", "WORKFLOW"),
    ("camunda-admin", "camunda BPM Administrators", "SYSTEM"),
):
    ensure_group(group_id, name, group_type)

users = (
    ("admin", "Admin", "User", "admin@localhost", "admin", ("camunda-admin",)),
    ("adminexamplecom", "Admin", "User", "admin@example.com", "camunda", ("ADMIN", "camunda-admin")),
    ("recruiterexamplecom", "Seed", "Recruiter", "recruiter@example.com", "camunda", ("RECRUITER",)),
    ("candidatedemoexamplecom", "Candidate", "Demo", "candidate-demo@example.com", "password123", ("CANDIDATE",)),
)
for user_id, first_name, last_name, email, user_password, groups in users:
    ensure_user(user_id, first_name, last_name, email, user_password)
    for group in groups:
        ensure_membership(group, user_id)

APPLICATION = 0
AUTHORIZATION = 4
FILTER = 5
PROCESS_DEFINITION = 6
TASK = 7
PROCESS_INSTANCE = 8
DECISION_DEFINITION = 10

for group in ("CANDIDATE", "RECRUITER", "ADMIN", "camunda-admin"):
    ensure_authorization(group, APPLICATION, "tasklist", ["ACCESS"])
    ensure_authorization(group, PROCESS_DEFINITION, "*", ["CREATE_INSTANCE", "READ"])

for group in ("ADMIN", "camunda-admin"):
    ensure_authorization(group, APPLICATION, "cockpit", ["ACCESS"])
    ensure_authorization(group, APPLICATION, "admin", ["ACCESS"])
    ensure_authorization(group, TASK, "*", ["READ", "UPDATE", "TASK_WORK"])
    ensure_authorization(group, PROCESS_INSTANCE, "*", ["READ", "UPDATE"])
    ensure_authorization(group, DECISION_DEFINITION, "*", ["READ"])
    ensure_authorization(group, AUTHORIZATION, "*", ["READ", "CREATE", "UPDATE", "DELETE"])

filter_read_groups = {
    "Задачи кандидата": ("CANDIDATE",),
    "Задачи рекрутера": ("RECRUITER",),
    "Задачи администратора": ("ADMIN",),
    "Мои активные задачи": ("CANDIDATE", "RECRUITER", "ADMIN"),
}
managed_filter_groups = ("CANDIDATE", "RECRUITER", "ADMIN", "camunda-admin")

for name, allowed_groups in filter_read_groups.items():
    filters = request("GET", "/filter?" + urlencode({"name": name})) or []
    for item in filters[1:]:
        filter_id = item.get("id")
        if filter_id:
            request("DELETE", f"/filter/{quote(filter_id)}")
            print(f"deleted duplicate filter {name}: {filter_id}")
    if filters:
        filter_id = filters[0].get("id")
        for group in managed_filter_groups:
            if group in allowed_groups:
                ensure_authorization(group, FILTER, filter_id, ["READ"])
            else:
                remove_authorization(group, FILTER, filter_id)

print("Helios Camunda bootstrap finished.")
PY
