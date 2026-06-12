#!/usr/bin/env python3
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import quote

import requests

from api_test_utils import (
    API,
    CheckError,
    admin_session,
    apply_to_vacancy,
    create_vacancy,
    filter_schedule_items_for_application,
    get_recruiter_applications,
    future_slot,
    invite_json,
    recruiter_session,
    register_candidate,
    respond_to_invitation,
    schedule_for_week,
    week_offset_for,
)


CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')


def ok(name: str, details: str = '') -> None:
    print(f'[OK Camunda] {name}' + (f' — {details}' if details else ''))


def camunda_user_id(email: str) -> str:
    return ''.join(ch for ch in email.strip().lower() if ch.isalnum())


def cv(value: Any) -> Dict[str, Any]:
    if isinstance(value, bool):
        return {'value': value, 'type': 'Boolean'}
    if isinstance(value, int):
        return {'value': value, 'type': 'Integer'}
    return {'value': value, 'type': 'String'}


def camunda_request(method: str, path: str, payload: Optional[Dict[str, Any]] = None,
                    expected: tuple[int, ...] = (200, 204)) -> requests.Response:
    resp = requests.request(method, f'{CAMUNDA_URL}{path}', json=payload, timeout=30)
    if resp.status_code not in expected:
        raise CheckError(f'Camunda {method} {path} -> {resp.status_code}. Body: {resp.text}')
    return resp


def wait_for(label: str, supplier: Callable[[], Any], predicate: Callable[[Any], bool],
             attempts: int = 60, delay: float = 1.0) -> Any:
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


def active_tasks(task_definition_key: str, process_instance_id: Optional[str] = None) -> List[Dict[str, Any]]:
    params = [
        ('taskDefinitionKey', task_definition_key),
        ('active', 'true'),
    ]
    if process_instance_id:
        params.append(('processInstanceId', process_instance_id))
    query = '&'.join(f'{quote(k)}={quote(v)}' for k, v in params)
    return camunda_request('GET', f'/task?{query}', expected=(200,)).json()


def wait_task(task_definition_key: str, process_instance_id: Optional[str] = None) -> Dict[str, Any]:
    tasks = wait_for(
        f'active Camunda task {task_definition_key}',
        lambda: active_tasks(task_definition_key, process_instance_id),
        lambda items: bool(items),
    )
    return tasks[0]


def variables(process_instance_id: str) -> Dict[str, Any]:
    raw = camunda_request(
        'GET',
        f'/process-instance/{quote(process_instance_id, safe="")}/variables',
        expected=(200,),
    ).json()
    return {name: item.get('value') for name, item in raw.items()}


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
        if str(variables(process_instance_id).get(variable_name)) == str(variable_value):
            return process_instance_id
    return None


def wait_process_by_variable(process_key: str, variable_name: str, variable_value: str) -> str:
    return wait_for(
        f'active {process_key} with {variable_name}={variable_value}',
        lambda: active_process_instance_id_by_variable(process_key, variable_name, variable_value),
        lambda value: value is not None,
    )


def history_activities(process_instance_id: str) -> List[Dict[str, Any]]:
    return camunda_request(
        'GET',
        f'/history/activity-instance?processInstanceId={quote(process_instance_id, safe="")}',
        expected=(200,),
    ).json()


def activity_finished(process_instance_id: str, activity_id: str) -> bool:
    return any(
        item.get('activityId') == activity_id and item.get('endTime') is not None
        for item in history_activities(process_instance_id)
    )


def wait_activity_finished(process_instance_id: str, activity_id: str) -> None:
    wait_for(
        f'finished activity {activity_id} in {process_instance_id}',
        lambda: activity_finished(process_instance_id, activity_id),
        bool,
    )


def assert_finished_activities(process_instance_id: str, activity_ids: List[str]) -> None:
    for activity_id in activity_ids:
        wait_activity_finished(process_instance_id, activity_id)


def assert_no_incidents(process_instance_id: str) -> None:
    incidents = camunda_request(
        'GET',
        f'/incident?processInstanceId={quote(process_instance_id, safe="")}',
        expected=(200,),
    ).json()
    if incidents:
        raise CheckError(f'Camunda process {process_instance_id} has incidents: {incidents}')


def wait_camunda_user_group(email: str, group_id: str) -> str:
    user_id = camunda_user_id(email)

    def has_group() -> bool:
        profile = camunda_request('GET', f'/user/{quote(user_id, safe="")}/profile', expected=(200, 404))
        if profile.status_code != 200:
            return False
        groups = camunda_request('GET', f'/group?member={quote(user_id, safe="")}', expected=(200,)).json()
        return any(item.get('id') == group_id for item in groups)

    wait_for(f'Camunda user {user_id} in {group_id}', has_group, bool)
    return user_id


def run_display_process(process_key: str, starter_user_id: str, display_task_id: str,
                        start_variables: Optional[Dict[str, Any]] = None,
                        first_task_id: Optional[str] = None,
                        first_task_variables: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    process_instance_id = start_process(
        process_key,
        f'camunda-smoke:{process_key}:{uuid.uuid4()}',
        {'starterUserId': starter_user_id, **(start_variables or {})},
    )
    if first_task_id:
        first_task = wait_task(first_task_id, process_instance_id)
        complete_task(first_task['id'], first_task_variables or {})
    display_task = wait_task(display_task_id, process_instance_id)
    data = variables(process_instance_id)
    if not data.get('uiTitle') or data.get('uiPayload') is None:
        raise CheckError(f'UI process {process_key} did not set uiTitle/uiPayload: {data}')
    assert_no_incidents(process_instance_id)
    return {'processInstanceId': process_instance_id, 'task': display_task, 'variables': data}


def assert_payload_contains(ui_result: Dict[str, Any], needle: str, label: str) -> None:
    payload = str(ui_result['variables'].get('uiPayload') or '')
    if needle not in payload:
        raise CheckError(f'{label} not found in Camunda UI payload: {payload[:1000]}')


def main() -> int:
    camunda_request('GET', '/version', expected=(200,))
    api = API()

    candidate = register_candidate(api, f'camunda_smoke_{uuid.uuid4().hex[:10]}@example.com')
    candidate_camunda_id = wait_camunda_user_group(candidate.email, 'CANDIDATE')
    ok('1. Регистрация кандидата', f'Camunda user {candidate_camunda_id} in CANDIDATE')

    recruiter = recruiter_session(api)
    admin = admin_session(api)
    recruiter_camunda_id = wait_camunda_user_group(recruiter.email, 'RECRUITER')
    admin_camunda_id = wait_camunda_user_group(admin.email, 'ADMIN')
    ok('2. Basic auth и /me', f'Camunda users {candidate_camunda_id}, {recruiter_camunda_id}, {admin_camunda_id}')

    vacancy = create_vacancy(api, recruiter, 'Camunda smoke vacancy', screening_threshold=1)
    vacancy_id = vacancy['id']
    vacancy_process_id = wait_process_by_variable('hhVacancyProcess', 'vacancyId', vacancy_id)
    assert_finished_activities(vacancy_process_id, ['ValidateCreateVacancyForm', 'CreateVacancyFromForm'])
    wait_task('VacancyCreatedResultTask', vacancy_process_id)
    vacancy_vars = variables(vacancy_process_id)
    if vacancy_vars.get('vacancyCreated') is not True or str(vacancy_vars.get('restAutoSubmit')).lower() != 'true':
        raise CheckError(f'Vacancy process variables do not prove Camunda REST path: {vacancy_vars}')
    assert_no_incidents(vacancy_process_id)
    ok('3. Создание вакансии', f'hhVacancyProcess={vacancy_process_id}')

    application = apply_to_vacancy(api, candidate, vacancy_id, 'camunda smoke')
    application_id = application['application_id']
    application_process_id = wait_process_by_variable('hhApplicationProcess', 'applicationId', application_id)
    assert_finished_activities(application_process_id, [
        'ValidateApplyToVacancyForm',
        'CreateApplicationFromForm',
        'AutoScreenApplication',
        'NotifyRecruiter',
    ])
    wait_task('RecruiterDecisionTask', application_process_id)
    recruiter_apps = get_recruiter_applications(api, recruiter, vacancy_id)
    if not any(str(item.get('application_id') or item.get('applicationId') or item.get('id')) == application_id for item in recruiter_apps):
        raise CheckError(f'Recruiter API list does not contain application {application_id}: {recruiter_apps}')
    recruiter_ui = run_display_process(
        'hhUiRecruiterApplicationView',
        recruiter_camunda_id,
        'DisplayRecruiterApplicationView',
        first_task_id='EnterRecruiterApplicationId',
        first_task_variables={'applicationIdText': application_id},
    )
    assert_payload_contains(recruiter_ui, application_id, 'application id')
    assert_no_incidents(application_process_id)
    ok('4. Создание заявки и просмотр рекрутером', f'hhApplicationProcess={application_process_id}')

    invite = invite_json(api, recruiter, application_id, future_slot(days=45), message='Camunda smoke invite')
    scheduled_at = invite.get('scheduled_at')
    if not scheduled_at:
        raise CheckError(f'Invite response does not include scheduled_at: {invite}')
    assert_finished_activities(application_process_id, [
        'RecruiterDecisionTask',
        'ValidateRecruiterDecisionForm',
        'WriteInvitationTask',
        'ValidateInvitationForm',
        'PersistInvitationToDb',
        'CreateInvitationInterview',
        'ReserveInvitationSlot',
        'RecordInvitationHistory',
        'NotifyInvitation',
    ])
    wait_task('CandidateInvitationResponseTask', application_process_id)
    assert_no_incidents(application_process_id)
    ok('5. Приглашение на интервью', f'interview={invite.get("interview_id")}')

    scheduled_dt = datetime.fromisoformat(scheduled_at.replace('Z', '+00:00')).astimezone(timezone.utc)
    schedule = schedule_for_week(api, recruiter, scheduled_dt)
    slots = filter_schedule_items_for_application(schedule, application_id)
    if not slots:
        raise CheckError(f'API schedule does not contain application {application_id}: {schedule}')
    schedule_ui = run_display_process(
        'hhUiRecruiterSchedule',
        recruiter_camunda_id,
        'DisplayRecruiterSchedule',
        first_task_id='EnterScheduleWeek',
        first_task_variables={'weekOffset': week_offset_for(scheduled_dt)},
    )
    assert_payload_contains(schedule_ui, application_id, 'schedule application id')
    wait_activity_finished(schedule_ui['processInstanceId'], 'LoadRecruiterSchedule')
    ok('6. Просмотр расписания', f'hhUiRecruiterSchedule={schedule_ui["processInstanceId"]}')

    response = respond_to_invitation(api, candidate, application_id, response_type='ACCEPT', message='Camunda smoke response')
    if response.status_code != 200:
        raise CheckError(f'Candidate response failed: {response.status_code} {response.text}')
    assert_finished_activities(application_process_id, [
        'CandidateInvitationResponseTask',
        'ValidateCandidateResponseForm',
        'CheckInvitationStillActive',
        'SaveCandidateResponse',
        'MarkCandidateResponseReceived',
        'RecordCandidateResponseHistory',
        'NotifyCandidateResponse',
    ])
    wait_task('CandidateResponseResultTask', application_process_id)
    assert_no_incidents(application_process_id)
    ok('7. Ответ на приглашение', 'CandidateResponseResultTask is active')

    notification_ui = run_display_process(
        'hhUiNotificationList',
        candidate_camunda_id,
        'DisplayNotificationList',
    )
    assert_payload_contains(notification_ui, application_id, 'notification application id')
    wait_activity_finished(notification_ui['processInstanceId'], 'LoadNotificationList')
    ok('8. Просмотр уведомлений', f'hhUiNotificationList={notification_ui["processInstanceId"]}')

    admin_ui = run_display_process(
        'hhUiAdminTimeoutReview',
        admin_camunda_id,
        'DisplayTimeoutReview',
        first_task_id='ConfirmTimeoutReview',
        first_task_variables={'manualRunConfirmed': True},
    )
    assert_payload_contains(admin_ui, 'schedulerProcessStarted', 'timeout review scheduler start')
    assert_payload_contains(admin_ui, 'Camunda BPMN loop hhTimeoutSchedulerProcess', 'timeout review BPMN owner')
    wait_activity_finished(admin_ui['processInstanceId'], 'RunTimeoutReview')
    ok('9. Админская джоба', f'hhUiAdminTimeoutReview={admin_ui["processInstanceId"]}')

    print('\nCamunda-level smoke flow passed')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as exc:
        print(f'[FAIL Camunda] {exc}')
        sys.exit(1)
