#!/usr/bin/env python3
import os
import sys
import time
import uuid
from datetime import timedelta
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import quote

import requests

from api_test_utils import (
    API,
    CheckError,
    admin_session,
    apply_to_vacancy,
    cancel_interview,
    close_vacancy,
    create_vacancy,
    future_slot,
    get_candidate_application_json,
    get_recruiter_application_json,
    invite_json,
    recruiter_session,
    register_candidate,
)


CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')


def camunda_user_id(email: str) -> str:
    return ''.join(ch for ch in email.strip().lower() if ch.isalnum())


def cv(value: Any, type_name: str = 'String') -> Dict[str, Any]:
    return {'value': value, 'type': type_name}


def camunda_request(method: str, path: str, payload: Optional[Dict[str, Any]] = None,
                    expected: tuple[int, ...] = (200, 204)) -> requests.Response:
    resp = requests.request(method, f'{CAMUNDA_URL}{path}', json=payload, timeout=20)
    if resp.status_code not in expected:
        raise CheckError(f'Camunda {method} {path} -> {resp.status_code}. Body: {resp.text}')
    return resp


def wait_for(label: str, supplier: Callable[[], Any], predicate: Callable[[Any], bool],
             attempts: int = 40, delay: float = 1.0) -> Any:
    last: Any = None
    for _ in range(attempts):
        last = supplier()
        if predicate(last):
            return last
        time.sleep(delay)
    raise CheckError(f'timed out waiting for {label}; last={last}')


def start_process(process_key: str, business_key: str, variables: Dict[str, Any]) -> str:
    body = {
        'businessKey': business_key,
        'variables': {name: cv(value) for name, value in variables.items()},
    }
    data = camunda_request(
        'POST',
        f'/process-definition/key/{quote(process_key, safe="")}/start',
        body,
        expected=(200,),
    ).json()
    process_instance_id = data.get('id')
    if not process_instance_id:
        raise CheckError(f'Camunda did not return process instance id for {process_key}: {data}')
    return process_instance_id


def complete_task(task_id: str, variables: Optional[Dict[str, Any]] = None) -> None:
    body = {'variables': {name: cv(value) for name, value in (variables or {}).items()}}
    camunda_request('POST', f'/task/{quote(task_id, safe="")}/complete', body, expected=(204,))


def active_tasks(task_definition_key: str, business_key: Optional[str] = None,
                 process_instance_id: Optional[str] = None,
                 variable_name: Optional[str] = None, variable_value: Optional[str] = None) -> List[Dict[str, Any]]:
    params = [
        ('taskDefinitionKey', task_definition_key),
        ('active', 'true'),
    ]
    if business_key:
        params.append(('processInstanceBusinessKey', business_key))
    if process_instance_id:
        params.append(('processInstanceId', process_instance_id))
    if variable_name and variable_value:
        params.append(('processVariables', f'{variable_name}_eq_{variable_value}'))
    query = '&'.join(f'{quote(k)}={quote(v)}' for k, v in params)
    return camunda_request('GET', f'/task?{query}', expected=(200,)).json()


def active_process_instance_id_by_variable(process_key: str, variable_name: str, variable_value: str) -> Optional[str]:
    instances = camunda_request(
        'GET',
        f'/process-instance?processDefinitionKey={quote(process_key, safe="")}&active=true',
        expected=(200,),
    ).json()
    for instance in instances:
        process_instance_id = instance.get('id')
        if not process_instance_id:
            continue
        variables = camunda_request(
            'GET',
            f'/process-instance/{quote(process_instance_id, safe="")}/variables',
            expected=(200,),
        ).json()
        if str((variables.get(variable_name) or {}).get('value')) == variable_value:
            return process_instance_id
    return None


def wait_process_instance_by_variable(process_key: str, variable_name: str, variable_value: str) -> str:
    return wait_for(
        f'active Camunda process {process_key} with {variable_name}={variable_value}',
        lambda: active_process_instance_id_by_variable(process_key, variable_name, variable_value),
        lambda value: value is not None,
    )


def wait_task(task_definition_key: str, **kwargs: Any) -> Dict[str, Any]:
    tasks = wait_for(
        f'Camunda task {task_definition_key}',
        lambda: active_tasks(task_definition_key, **kwargs),
        lambda items: bool(items),
    )
    return tasks[0]


def wait_no_task(task_definition_key: str, **kwargs: Any) -> None:
    wait_for(
        f'no active Camunda task {task_definition_key}',
        lambda: active_tasks(task_definition_key, **kwargs),
        lambda items: not items,
    )


def wait_application_status(api: API, recruiter, application_id: str, expected: str) -> Dict[str, Any]:
    return wait_for(
        f'application {application_id} status {expected}',
        lambda: get_recruiter_application_json(api, recruiter, application_id),
        lambda data: data.get('status') == expected,
    )


def unique_slot() -> Any:
    offset = int(uuid.uuid4().hex[:8], 16)
    return future_slot(days=60 + offset % 180, hour_shift=int(offset / 180) % 10)


def scenario_recruiter_cancel_returns_to_camunda_review(api: API) -> None:
    recruiter = recruiter_session(api)
    candidate = register_candidate(api, f'camunda_cancel_{uuid.uuid4().hex[:10]}@example.com')

    vacancy = create_vacancy(api, recruiter, 'Camunda cancel/reinvite vacancy', screening_threshold=1)
    application = apply_to_vacancy(api, candidate, vacancy['id'], 'cancel reinvite bpmn')
    application_id = application['application_id']
    wait_application_status(api, recruiter, application_id, 'ON_RECRUITER_REVIEW')

    first_invite = invite_json(api, recruiter, application_id, unique_slot(), message='First Camunda invite')
    interview_id = first_invite.get('interview_id')
    if not interview_id:
        raise CheckError(f'Invite did not create interview: {first_invite}')
    wait_application_status(api, recruiter, application_id, 'INVITED')
    application_process_id = wait_process_instance_by_variable(
        'hhApplicationProcess',
        'applicationId',
        application_id,
    )
    wait_task('CandidateInvitationResponseTask', process_instance_id=application_process_id)

    cancel_interview(api, recruiter, interview_id, reason='Scenario test reschedule')
    wait_application_status(api, recruiter, application_id, 'ON_RECRUITER_REVIEW')
    wait_no_task('CandidateInvitationResponseTask', process_instance_id=application_process_id)
    wait_task('RecruiterDecisionTask', process_instance_id=application_process_id)

    second_invite = invite_json(
        api,
        recruiter,
        application_id,
        unique_slot() + timedelta(hours=4),
        message='Second Camunda invite after cancel',
    )
    if not second_invite.get('interview_id') or second_invite.get('interview_id') == interview_id:
        raise CheckError(f'Reinvite did not create a fresh interview: {second_invite}')
    candidate_view = wait_for(
        'candidate sees second invitation',
        lambda: get_candidate_application_json(api, candidate, application_id),
        lambda data: data.get('status') == 'INVITED' and data.get('interview') is not None,
    )
    candidate_interview_id = (
        candidate_view['interview'].get('interview_id')
        or candidate_view['interview'].get('interviewId')
        or candidate_view['interview'].get('id')
    )
    if candidate_interview_id != second_invite['interview_id']:
        raise CheckError(f'Candidate sees wrong interview after reinvite: {candidate_view} vs {second_invite}')
    print('OK Camunda scenario: recruiter cancel returns application to review and allows reinvite')


def scenario_close_vacancy_after_result_task(api: API) -> None:
    recruiter = recruiter_session(api)
    vacancy = create_vacancy(api, recruiter, 'Camunda close after result task', screening_threshold=1)
    vacancy_id = vacancy['id']

    vacancy_process_id = wait_process_instance_by_variable('hhVacancyProcess', 'vacancyId', vacancy_id)
    wait_task('VacancyCreatedResultTask', process_instance_id=vacancy_process_id)
    closed = close_vacancy(api, recruiter, vacancy_id, 'Scenario closes via Camunda')
    if closed.get('status') != 'CLOSED':
        raise CheckError(f'Vacancy close did not return CLOSED status: {closed}')
    wait_task('VacancyClosedResultTask', process_instance_id=vacancy_process_id)
    print('OK Camunda scenario: vacancy close flows through Camunda from result task')


def scenario_tasklist_ui_processes(api: API) -> None:
    admin = admin_session(api)
    recruiter = recruiter_session(api)
    candidate = register_candidate(api, f'camunda_ui_{uuid.uuid4().hex[:10]}@example.com')
    starter_candidate = camunda_user_id(candidate.email)
    starter_recruiter = camunda_user_id(recruiter.email)
    starter_admin = camunda_user_id(admin.email)

    ui_cases = [
        ('hhUiCandidateVacancyList', starter_candidate, {}, 'DisplayCandidateVacancyList'),
        ('hhUiCandidateApplicationList', starter_candidate, {}, 'DisplayCandidateApplicationList'),
        ('hhUiRecruiterVacancyList', starter_recruiter, {}, 'DisplayRecruiterVacancyList'),
        ('hhUiRecruiterApplicationList', starter_recruiter, {}, 'DisplayRecruiterApplicationList'),
        ('hhUiNotificationList', starter_recruiter, {}, 'DisplayNotificationList'),
    ]
    for process_key, starter, extra_variables, display_task in ui_cases:
        business_key = f'ui:{process_key}:{uuid.uuid4()}'
        process_instance_id = start_process(
            process_key,
            business_key,
            {'starterUserId': starter, **extra_variables},
        )
        task = wait_task(display_task, process_instance_id=process_instance_id)
        complete_task(task['id'])
        print(f'OK Camunda UI process reached display form: {process_key} -> {display_task}')

    admin_business_key = f'ui:hhUiAdminTimeoutReview:{uuid.uuid4()}'
    admin_process_id = start_process('hhUiAdminTimeoutReview', admin_business_key, {'starterUserId': starter_admin})
    confirm_task = wait_task('ConfirmTimeoutReview', process_instance_id=admin_process_id)
    complete_task(confirm_task['id'], {'manualRunConfirmed': True})
    display_task = wait_task('DisplayTimeoutReview', process_instance_id=admin_process_id)
    complete_task(display_task['id'])
    print('OK Camunda UI process: admin timeout review manual run reaches result form')


def main() -> int:
    try:
        camunda_request('GET', '/version', expected=(200,))
    except CheckError as exc:
        print(f'Camunda REST is unavailable: {exc}')
        return 1

    api = API()
    scenario_recruiter_cancel_returns_to_camunda_review(api)
    scenario_close_vacancy_after_result_task(api)
    scenario_tasklist_ui_processes(api)
    return 0


if __name__ == '__main__':
    sys.exit(main())
