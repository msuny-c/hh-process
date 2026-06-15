#!/usr/bin/env python3
import os
import sys
import time
from typing import Any, Callable, Dict, List
from urllib.parse import quote

import requests

from api_test_utils import (
    API,
    CheckError,
    admin_session,
    apply_to_vacancy,
    create_recruiter,
    create_vacancy,
    expect_status,
    get_recruiter_application_json,
    register_candidate,
    recruiter_session,
)


CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')


def camunda_user_id(email: str) -> str:
    return ''.join(ch for ch in email.strip().lower() if ch.isalnum())


def camunda_get(path: str) -> Any:
    resp = requests.get(f'{CAMUNDA_URL}{path}', timeout=20)
    if resp.status_code != 200:
        raise CheckError(f'Camunda GET {path} -> {resp.status_code}. Body: {resp.text}')
    return resp.json()


def wait_for(label: str, supplier: Callable[[], Any], predicate: Callable[[Any], bool],
             attempts: int = 40, delay: float = 1.0) -> Any:
    last = None
    for _ in range(attempts):
        last = supplier()
        if predicate(last):
            return last
        time.sleep(delay)
    raise CheckError(f'Timed out waiting for {label}: {last}')


def active_tasks_by_variable(task_definition_key: str, application_id: str, extra: str = '') -> List[Dict[str, Any]]:
    query = (
        f'/task?active=true'
        f'&taskDefinitionKey={quote(task_definition_key, safe="")}'
        f'&processVariables={quote(f"applicationId_eq_{application_id}", safe="")}'
        f'{extra}'
    )
    return camunda_get(query)


def wait_application_status(api: API, recruiter, application_id: str, expected: str) -> Dict[str, Any]:
    return wait_for(
        f'application {application_id} status {expected}',
        lambda: get_recruiter_application_json(api, recruiter, application_id),
        lambda data: data.get('status') == expected,
    )


def main() -> int:
    api = API()
    admin = admin_session(api)
    owner = recruiter_session(api)
    other = create_recruiter(api, admin)
    candidate = register_candidate(api)

    vacancy = create_vacancy(api, owner, 'Recruiter isolation vacancy', screening_threshold=1)
    application = apply_to_vacancy(api, candidate, vacancy['id'], 'recruiter isolation')
    application_id = application['application_id']
    wait_application_status(api, owner, application_id, 'ON_RECRUITER_REVIEW')

    other_view = api.request(
        'GET',
        f'/api/v1/recruiters/applications/{application_id}',
        auth=other.auth,
        expected=[403],
    )
    expect_status(other_view, 403, 'other recruiter must not read application owned by first recruiter')

    other_invite = api.request(
        'POST',
        f'/api/v1/recruiters/applications/{application_id}/invite',
        auth=other.auth,
        expected=[403],
        payload={
            'message': 'Not my application',
            'scheduled_at': '2030-01-01T10:00:00Z',
            'duration_minutes': 60,
        },
    )
    expect_status(other_invite, 403, 'other recruiter must not invite candidate for foreign application')

    owner_camunda_id = camunda_user_id(owner.email)
    other_camunda_id = camunda_user_id(other.email)
    tasks = wait_for(
        'personal recruiter decision task',
        lambda: active_tasks_by_variable('RecruiterDecisionTask', application_id),
        lambda items: bool(items) and items[0].get('assignee') == owner_camunda_id,
    )
    task = tasks[0]
    if task.get('assignee') != owner_camunda_id:
        raise CheckError(f'RecruiterDecisionTask assigned to wrong user: {task}')

    other_assignee_tasks = active_tasks_by_variable(
        'RecruiterDecisionTask',
        application_id,
        f'&assignee={quote(other_camunda_id, safe="")}',
    )
    if other_assignee_tasks:
        raise CheckError(f'Other recruiter sees foreign assigned Camunda task: {other_assignee_tasks}')

    group_tasks = active_tasks_by_variable(
        'RecruiterDecisionTask',
        application_id,
        '&candidateGroup=RECRUITER',
    )
    if group_tasks:
        raise CheckError(f'RecruiterDecisionTask must not remain a RECRUITER group task: {group_tasks}')

    print('Recruiter task isolation scenarios passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
