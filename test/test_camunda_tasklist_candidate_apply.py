#!/usr/bin/env python3
import sys
import uuid

from api_test_utils import API, CheckError, create_vacancy, get_recruiter_applications, recruiter_session, register_candidate
from test_camunda_smoke_flow import (
    assert_finished_activities,
    assert_no_incidents,
    complete_task,
    history_activities,
    start_process,
    variables,
    wait_camunda_user_group,
    wait_for,
    wait_task,
)


def main() -> int:
    api = API()
    candidate = register_candidate(api, f'tasklist_apply_{uuid.uuid4().hex[:10]}@example.com')
    recruiter = recruiter_session(api)

    candidate_camunda_id = wait_camunda_user_group(candidate.email, 'CANDIDATE')
    vacancy = create_vacancy(api, recruiter, 'Tasklist-only candidate apply', screening_threshold=1)

    process_instance_id = start_process(
        'hhApplicationProcess',
        f'tasklist-apply:{uuid.uuid4()}',
        {
            'starterUserId': candidate_camunda_id,
            'vacancyId': vacancy['id'],
            'vacancyTitle': vacancy['title'],
        },
    )

    apply_task = wait_task('ApplyToVacancyTask', process_instance_id)
    complete_task(apply_task['id'], {
        'vacancyId': vacancy['id'],
        'resumeText': 'Python Spring PostgreSQL Docker experience from Camunda Tasklist form',
        'coverLetter': 'Created through Camunda Form, not through candidate REST apply endpoint.',
    })

    def application_id():
        return variables(process_instance_id).get('applicationId')

    application_id_value = wait_for(
        'applicationId created by Camunda form path',
        application_id,
        lambda value: value is not None,
        attempts=90,
    )

    assert_finished_activities(process_instance_id, [
        'ValidateApplyToVacancyForm',
        'CreateApplicationFromForm',
        'AutoScreenApplication',
        'EvaluateAutoScreeningDecision',
        'SaveAutoScreenDecision',
        'NotifyRecruiter',
    ])

    def notification_process_id():
        for item in history_activities(process_instance_id):
            if item.get('activityId') == 'NotifyRecruiter' and item.get('calledProcessInstanceId'):
                return item.get('calledProcessInstanceId')
        return None

    notification_instance_id = wait_for(
        'notification subprocess called by NotifyRecruiter',
        notification_process_id,
        lambda value: value is not None,
        attempts=30,
    )
    assert_finished_activities(notification_instance_id, [
        'PrepareNotificationTemplateDecision',
        'EvaluateNotificationTemplate',
        'DispatchNotification',
    ])
    wait_task('RecruiterDecisionTask', process_instance_id)
    assert_no_incidents(process_instance_id)

    recruiter_apps = get_recruiter_applications(api, recruiter, vacancy['id'])
    if not any(str(item.get('application_id') or item.get('applicationId') or item.get('id')) == str(application_id_value)
               for item in recruiter_apps):
        raise CheckError(f'Recruiter list does not contain Camunda Form application {application_id_value}: {recruiter_apps}')

    data = variables(process_instance_id)
    if data.get('autoScreeningDecisionOwner') != 'Camunda DMN hhAutoScreening':
        raise CheckError(f'Auto screening was not marked as Camunda DMN-owned: {data}')

    print('Camunda Tasklist candidate apply path passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
