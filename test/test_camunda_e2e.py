#!/usr/bin/env python3
import sys
import time
from typing import Any, Callable, Dict

from api_test_utils import (
    API,
    CheckError,
    apply_to_vacancy,
    create_vacancy,
    future_slot,
    get_candidate_application_json,
    invite_json,
    recruiter_session,
    register_candidate,
    respond_to_invitation,
)


def wait_for(label: str, supplier: Callable[[], Dict[str, Any]], predicate: Callable[[Dict[str, Any]], bool],
             attempts: int = 30, delay: float = 1.0) -> Dict[str, Any]:
    last: Dict[str, Any] = {}
    for _ in range(attempts):
        last = supplier()
        if predicate(last):
            return last
        time.sleep(delay)
    raise CheckError(f'timed out waiting for {label}; last={last}')


def main() -> int:
    api = API()
    recruiter = recruiter_session(api)
    candidate = register_candidate(api, prefix_email())

    vacancy = create_vacancy(api, recruiter, 'Camunda E2E vacancy', screening_threshold=1)
    application = apply_to_vacancy(api, candidate, vacancy['id'], 'Camunda BPMN dynamic path')
    application_id = application['application_id']

    invite = wait_for_invite(api, recruiter, application_id)
    if not invite.get('interview_id') or not invite.get('schedule_slot_id'):
        raise CheckError(f'Camunda invite did not create composite artifacts: {invite}')

    candidate_after_invite = get_candidate_application_json(api, candidate, application_id)
    if candidate_after_invite.get('status') != 'INVITED':
        raise CheckError(f'Candidate must see INVITED after Camunda invite: {candidate_after_invite}')

    response = respond_to_invitation(api, candidate, application_id, response_type='ACCEPT', message='I will join')
    if response.status_code != 200:
        raise CheckError(f'Camunda candidate response failed: {response.status_code} {response.text}')

    final_view = wait_for(
        'Camunda response persistence',
        lambda: get_candidate_application_json(api, candidate, application_id),
        lambda data: data.get('status') == 'RESPONDED',
    )
    if final_view.get('interview') is None:
        raise CheckError(f'Interview should remain visible after response: {final_view}')

    print('OK Camunda e2e process completed')
    return 0


def prefix_email() -> str:
    import uuid

    return f'camunda_e2e_{uuid.uuid4().hex[:10]}@example.com'


def wait_for_invite(api: API, recruiter, application_id: str) -> Dict[str, Any]:
    last_error = ''
    scheduled_at = unique_future_slot()
    for _ in range(30):
        try:
            return invite_json(
                api,
                recruiter,
                application_id,
                scheduled_at,
                message='Camunda-created interview',
            )
        except CheckError as exc:
            last_error = str(exc)
            if 'Camunda recruiter invitation task is not active' not in last_error \
                    and 'Application is not in ON_RECRUITER_REVIEW status' not in last_error:
                raise
            time.sleep(1)
    raise CheckError(f'timed out waiting for Camunda recruiter task; last={last_error}')


def unique_future_slot():
    import uuid

    offset = int(uuid.uuid4().hex[:8], 16)
    return future_slot(days=30 + offset % 365, hour_shift=(offset


if __name__ == '__main__':
    sys.exit(main())
