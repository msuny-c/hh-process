#!/usr/bin/env python3
import os
import sys
import time
from urllib.parse import quote

import requests


CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')


def cv(value):
    if isinstance(value, bool):
        return {'value': value, 'type': 'Boolean'}
    if isinstance(value, int):
        return {'value': value, 'type': 'Integer'}
    return {'value': value, 'type': 'String'}


def wait_camunda() -> None:
    for _ in range(60):
        try:
            if requests.get(f'{CAMUNDA_URL}/version', timeout=5).status_code == 200:
                return
        except Exception:
            pass
        time.sleep(2)
    raise AssertionError(f'Camunda REST is unavailable: {CAMUNDA_URL}')


def evaluate(decision_key: str, variables: dict):
    resp = requests.post(
        f'{CAMUNDA_URL}/decision-definition/key/{quote(decision_key, safe="")}/evaluate',
        json={'variables': {name: cv(value) for name, value in variables.items()}},
        timeout=20,
    )
    if resp.status_code != 200:
        raise AssertionError(f'DMN {decision_key} evaluation failed: {resp.status_code} {resp.text}')
    data = resp.json()
    if not data:
        raise AssertionError(f'DMN {decision_key} returned no result')
    return {name: item.get('value') for name, item in data[0].items()}


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    wait_camunda()

    permission = evaluate('hhOperationPermissions', {
        'permissionRole': 'RECRUITER',
        'permissionOperation': 'CREATE_VACANCY',
        'permissionOwnership': True,
    })
    ensure(permission.get('allowed') is True, f'permission allow rule failed: {permission}')

    denied = evaluate('hhOperationPermissions', {
        'permissionRole': 'CANDIDATE',
        'permissionOperation': 'CREATE_VACANCY',
        'permissionOwnership': True,
    })
    ensure(denied.get('allowed') is False, f'permission deny rule failed: {denied}')

    screening_passed = evaluate('hhAutoScreening', {'screeningScoreDelta': 0})
    ensure(screening_passed.get('passed') is True, f'autoscreening pass rule failed: {screening_passed}')

    screening_failed = evaluate('hhAutoScreening', {'screeningScoreDelta': -1})
    ensure(screening_failed.get('passed') is False, f'autoscreening fail rule failed: {screening_failed}')

    transition = evaluate('hhStatusTransitions', {
        'currentStatus': 'ON_RECRUITER_REVIEW',
        'statusAction': 'INVITE_APPLICATION',
        'requestedStatus': '',
    })
    ensure(transition.get('allowed') is True and transition.get('nextStatus') == 'INVITED',
           f'status transition invite rule failed: {transition}')

    blocked_transition = evaluate('hhStatusTransitions', {
        'currentStatus': 'SCREENING_FAILED',
        'statusAction': 'RESPOND_INVITATION',
        'requestedStatus': '',
    })
    ensure(blocked_transition.get('allowed') is False,
           f'status transition deny rule failed: {blocked_transition}')

    template = evaluate('hhNotificationTemplates', {
        'notificationKind': 'INVITATION',
        'notificationStatus': 'INVITED',
        'recipientRole': 'CANDIDATE',
    })
    code = template.get('templateCode') or ''
    ensure(code.startswith('INVITATION|INVITATION|CANDIDATE|'),
           f'notification template rule failed: {template}')

    print('Camunda DMN runtime decisions passed')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except AssertionError as exc:
        print(exc)
        sys.exit(1)
