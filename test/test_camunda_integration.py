#!/usr/bin/env python3
import os
import sys
import time
from urllib.parse import quote
import requests

CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')


def get(path):
    return requests.get(f'{CAMUNDA_URL}{path}', timeout=10)


def wait_for_camunda() -> bool:
    for _ in range(60):
        try:
            if get('/version').status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def process_deployed(key: str) -> bool:
    resp = get(f'/process-definition?key={key}&latestVersion=true')
    return resp.status_code == 200 and bool(resp.json())


def camunda_user_id(email: str) -> str:
    return ''.join(ch for ch in email.strip().lower() if ch.isalnum())


def user_synced(email: str, expected_group: str) -> bool:
    user_id = camunda_user_id(email)
    profile = get(f'/user/{quote(user_id, safe="")}/profile')
    if profile.status_code != 200:
        return False
    data = profile.json()
    if data.get('id') != user_id or data.get('email') != email:
        return False
    groups = get(f'/group?member={quote(user_id, safe="")}')
    if groups.status_code != 200:
        return False
    return any(item.get('id') == expected_group for item in groups.json())


def start_authorization_exists(group_id: str, process_key: str) -> bool:
    resp = get(
        f'/authorization?type=1&groupIdIn={quote(group_id, safe="")}'
        f'&resourceType=6&resourceId={quote(process_key, safe="")}'
    )
    return resp.status_code == 200 and bool(resp.json())


def main() -> int:
    if not wait_for_camunda():
        print(f'Camunda REST is unavailable: {CAMUNDA_URL}')
        return 1

    required = [
        'hhApplicationProcess',
        'hhVacancyProcess',
        'hhTimeoutSchedulerProcess',
        'hhAdminInterviewResetProcess',
    ]
    for key in required:
        for _ in range(60):
            if process_deployed(key):
                print(f'OK process definition deployed: {key}')
                break
            time.sleep(2)
        else:
            print(f'Process definition was not deployed: {key}')
            return 1

    required_users = [
        ('admin@example.com', 'ADMIN'),
        ('recruiter@example.com', 'RECRUITER'),
    ]
    for email, group in required_users:
        for _ in range(60):
            if user_synced(email, group):
                print(f'OK Camunda user synced: {camunda_user_id(email)} -> {group}')
                break
            time.sleep(2)
        else:
            print(f'Camunda user was not synced: {email} -> {group}')
            return 1

    required_authorizations = [
        ('CANDIDATE', 'hhApplicationProcess'),
        ('RECRUITER', 'hhVacancyProcess'),
        ('ADMIN', 'hhAdminInterviewResetProcess'),
        ('ADMIN', 'hhTimeoutSchedulerProcess'),
    ]
    for group, process_key in required_authorizations:
        if not start_authorization_exists(group, process_key):
            print(f'Camunda start authorization is missing: {group} -> {process_key}')
            return 1
        print(f'OK Camunda start authorization: {group} -> {process_key}')

    return 0


if __name__ == '__main__':
    sys.exit(main())
