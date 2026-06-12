#!/usr/bin/env python3
import os
import sys
import time
from urllib.parse import quote
import requests

CAMUNDA_URL = os.getenv('CAMUNDA_URL', 'http://localhost:8081/engine-rest').rstrip('/')
DEPLOYMENT_NAME = os.getenv('CAMUNDA_DEPLOYMENT_NAME', 'hh-process-bpmn')


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


def process_absent(key: str) -> bool:
    resp = get(f'/process-definition?key={key}')
    return resp.status_code == 200 and not bool(resp.json())


def process_xml(key: str) -> str:
    resp = get(f'/process-definition/key/{quote(key, safe="")}/xml')
    if resp.status_code != 200:
        return ''
    return resp.json().get('bpmn20Xml') or ''


def filter_exists(name: str) -> bool:
    resp = get(f'/filter?name={quote(name, safe="")}')
    return resp.status_code == 200 and bool(resp.json())


def form_resource_deployed(name: str) -> bool:
    deployments = get(f'/deployment?name={quote(DEPLOYMENT_NAME, safe="")}')
    if deployments.status_code != 200:
        return False
    for deployment in deployments.json():
        deployment_id = deployment.get('id')
        if not deployment_id:
            continue
        resources = get(f'/deployment/{quote(deployment_id, safe="")}/resources')
        if resources.status_code != 200:
            continue
        if any(item.get('name') == name for item in resources.json()):
            return True
    return False


def timer_start_job_exists(key: str) -> bool:
    resp = get(f'/job-definition?processDefinitionKey={quote(key, safe="")}')
    if resp.status_code != 200:
        return False
    return any(item.get('jobType') == 'timer-start-event' and not item.get('suspended') for item in resp.json())


def scheduled_timer_job_exists(key: str) -> bool:
    resp = get(f'/job?processDefinitionKey={quote(key, safe="")}')
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

    required_processes = {
        'hhApplicationProcess': 'CANDIDATE,ADMIN',
        'hhVacancyProcess': 'RECRUITER,ADMIN',
        'hhTimeoutSchedulerProcess': 'ADMIN',
        'hhAdminInterviewResetProcess': 'ADMIN',
        'hhVacancyStatusUpdateProcess': 'RECRUITER,ADMIN',
        'hhRecruiterInterviewCancelProcess': 'RECRUITER,ADMIN',
        'hhNotificationProcess': '',
        'hhUiCandidateVacancyList': 'CANDIDATE,ADMIN',
        'hhUiCandidateApplicationList': 'CANDIDATE,ADMIN',
        'hhUiCandidateApplicationView': 'CANDIDATE,ADMIN',
        'hhUiRecruiterVacancyList': 'RECRUITER,ADMIN',
        'hhUiRecruiterApplicationList': 'RECRUITER,ADMIN',
        'hhUiRecruiterApplicationView': 'RECRUITER,ADMIN',
        'hhUiRecruiterSchedule': 'RECRUITER,ADMIN',
        'hhUiNotificationList': 'CANDIDATE,RECRUITER,ADMIN',
        'hhUiAdminTimeoutReview': 'ADMIN',
    }
    for key in required_processes:
        for _ in range(60):
            if process_deployed(key):
                print(f'OK process definition deployed: {key}')
                break
            time.sleep(2)
        else:
            print(f'Process definition was not deployed: {key}')
            return 1

    for key, expected_groups in required_processes.items():
        xml = process_xml(key)
        if not expected_groups:
            print(f'OK BPMN reusable process deployed: {key}')
            continue
        expected = f'camunda:candidateStarterGroups="{expected_groups}"'
        if expected not in xml:
            print(f'Process {key} is missing starter groups in BPMN XML: {expected}')
            return 1
        print(f'OK BPMN starter groups: {key} -> {expected_groups}')

    app_xml = process_xml('hhApplicationProcess')
    if 'camunda:topic="notification-send"' in app_xml:
        print('Deprecated notification-send topic is still present in executable application BPMN')
        return 1
    print('OK executable application BPMN uses split notification/message topics')

    required_decisions = {
        'hhOperationPermissions',
        'hhAutoScreening',
        'hhStatusTransitions',
        'hhNotificationTemplates',
    }
    for key in required_decisions:
        resp = get(f'/decision-definition?key={quote(key, safe="")}&latestVersion=true')
        if resp.status_code != 200 or not resp.json():
            print(f'Camunda decision definition is missing: {key}')
            return 1
        decision = resp.json()[0]
        if decision.get('historyTimeToLive') != 30:
            print(f'Camunda decision {key} must have historyTimeToLive=30: {decision}')
            return 1
        print(f'OK Camunda decision definition deployed: {key}')

    removed_demo_processes = ['invoice', 'ReviewInvoice']
    for key in removed_demo_processes:
        for _ in range(30):
            if process_absent(key):
                print(f'OK default Camunda demo process removed: {key}')
                break
            time.sleep(1)
        else:
            print(f'Default Camunda demo process is still deployed: {key}')
            return 1

    required_forms = [
        'admin-reset-result.form',
        'admin-timeout-run.form',
        'application-id-input.form',
        'application-result.form',
        'apply-to-vacancy.form',
        'cancel-interview.form',
        'close-vacancy.form',
        'create-vacancy.form',
        'invitation-response.form',
        'invitation.form',
        'recruiter-decision.form',
        'reset-interview.form',
        'schedule-week-input.form',
        'ui-json-display.form',
        'vacancy-result.form',
        'vacancy-status-update.form',
    ]
    for name in required_forms:
        if not form_resource_deployed(name):
            print(f'Camunda form resource was not deployed: {name}')
            return 1
        print(f'OK Camunda form resource deployed: {name}')

    required_filters = [
        'Задачи кандидата',
        'Задачи рекрутера',
        'Задачи администратора',
        'Мои активные задачи',
    ]
    for name in required_filters:
        if not filter_exists(name):
            print(f'Camunda Tasklist filter is missing: {name}')
            return 1
        print(f'OK Camunda Tasklist filter: {name}')

    for name in ['Accounting', 'John\'s Tasks', 'Mary\'s Tasks', 'Peter\'s Tasks']:
        if filter_exists(name):
            print(f'Default Camunda demo filter is still present: {name}')
            return 1
    print('OK default Camunda demo Tasklist filters removed')

    for _ in range(30):
        if timer_start_job_exists('hhTimeoutSchedulerProcess') and scheduled_timer_job_exists('hhTimeoutSchedulerProcess'):
            print('OK Camunda timer scheduler job is active')
            break
        time.sleep(1)
    else:
        print('Camunda timer scheduler job is not active')
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
        ('CANDIDATE', 'hhUiCandidateVacancyList'),
        ('CANDIDATE', 'hhUiCandidateApplicationList'),
        ('CANDIDATE', 'hhUiCandidateApplicationView'),
        ('CANDIDATE', 'hhUiNotificationList'),
        ('RECRUITER', 'hhVacancyProcess'),
        ('RECRUITER', 'hhVacancyStatusUpdateProcess'),
        ('RECRUITER', 'hhRecruiterInterviewCancelProcess'),
        ('RECRUITER', 'hhUiRecruiterVacancyList'),
        ('RECRUITER', 'hhUiRecruiterApplicationList'),
        ('RECRUITER', 'hhUiRecruiterApplicationView'),
        ('RECRUITER', 'hhUiRecruiterSchedule'),
        ('RECRUITER', 'hhUiNotificationList'),
        ('ADMIN', 'hhAdminInterviewResetProcess'),
        ('ADMIN', 'hhTimeoutSchedulerProcess'),
        ('ADMIN', 'hhUiAdminTimeoutReview'),
    ]
    for group, process_key in required_authorizations:
        if not start_authorization_exists(group, process_key):
            print(f'Camunda start authorization is missing: {group} -> {process_key}')
            return 1
        print(f'OK Camunda start authorization: {group} -> {process_key}')

    return 0


if __name__ == '__main__':
    sys.exit(main())
