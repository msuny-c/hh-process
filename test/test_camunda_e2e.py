#!/usr/bin/env python3
import os
import sys
import time
from typing import Any, Callable, Dict

import psycopg

from api_test_utils import (
    API,
    CheckError,
    apply_to_vacancy,
    create_vacancy,
    filter_schedule_items_for_application,
    find_notification,
    future_slot,
    get_candidate_application_json,
    invite_json,
    notifications,
    recruiter_session,
    register_candidate,
    respond_to_invitation,
    schedule_for_week,
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
    wait_for_db_application_status(application_id, 'ON_RECRUITER_REVIEW')

    recruiter_notifications = wait_for_notifications(
        api,
        recruiter,
        application_id,
        'NEW_APPLICATION',
        'recruiter new application notification',
    )
    assert_db_notification(application_id, recruiter.user_id, 'NEW_APPLICATION')

    invite = wait_for_invite(api, recruiter, application_id)
    if not invite.get('interview_id') or not invite.get('schedule_slot_id'):
        raise CheckError(f'Camunda invite did not create composite artifacts: {invite}')

    candidate_after_invite = get_candidate_application_json(api, candidate, application_id)
    if candidate_after_invite.get('status') != 'INVITED':
        raise CheckError(f'Candidate must see INVITED after Camunda invite: {candidate_after_invite}')

    invited_db = fetch_db_state(application_id)
    if invited_db['application']['status'] != 'INVITED':
        raise CheckError(f'DB application must be INVITED after invite: {invited_db}')
    if invited_db['application']['invitation_sent_at'] is None or invited_db['application']['invitation_expires_at'] is None:
        raise CheckError(f'DB invitation timestamps must be saved: {invited_db}')
    if len(invited_db['interviews']) != 1 or invited_db['interviews'][0]['status'] != 'SCHEDULED':
        raise CheckError(f'DB scheduled interview must be saved: {invited_db}')
    if len(invited_db['schedule_slots']) != 1 or invited_db['schedule_slots'][0]['status'] != 'RESERVED':
        raise CheckError(f'DB reserved schedule slot must be saved: {invited_db}')

    schedule = schedule_for_week(api, recruiter, unique_future_slot_from_iso(invite['scheduled_at']))
    app_slots = filter_schedule_items_for_application(schedule, application_id)
    if not any(item.get('slot_id') == invite['schedule_slot_id'] and item.get('status') == 'RESERVED' for item in app_slots):
        raise CheckError(f'API schedule must expose reserved Camunda slot: {app_slots}')

    wait_for_notifications(api, candidate, application_id, 'INVITATION', 'candidate invitation notification')
    assert_db_notification(application_id, candidate.user_id, 'INVITATION')

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

    responded_db = fetch_db_state(application_id)
    if responded_db['application']['status'] != 'INVITATION_RESPONDED':
        raise CheckError(f'DB application must be INVITATION_RESPONDED after response: {responded_db}')
    if responded_db['application']['response_received_at'] is None:
        raise CheckError(f'DB response timestamp must be saved: {responded_db}')
    if len(responded_db['responses']) != 1 or responded_db['responses'][0]['response_type'] != 'ACCEPT':
        raise CheckError(f'DB invitation response must be saved: {responded_db}')
    if len(responded_db['interviews']) != 1 or responded_db['interviews'][0]['status'] != 'SCHEDULED':
        raise CheckError(f'DB interview must remain scheduled after response: {responded_db}')
    if len(responded_db['schedule_slots']) != 1 or responded_db['schedule_slots'][0]['status'] != 'RESERVED':
        raise CheckError(f'DB schedule slot must remain reserved after response: {responded_db}')

    wait_for_notifications(api, recruiter, application_id, 'INVITATION_RESPONSE', 'recruiter response notification')
    assert_db_notification(application_id, recruiter.user_id, 'INVITATION_RESPONSE')

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
    return future_slot(days=30 + offset % 365, hour_shift=int(offset / 365) % 18)


def unique_future_slot_from_iso(value: str):
    from datetime import datetime

    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def pg_dsn() -> str:
    host = os.getenv('POSTGRES_HOST', 'localhost')
    port = os.getenv('POSTGRES_PORT', '5432')
    db = os.getenv('POSTGRES_DB', 'postgres')
    user = os.getenv('POSTGRES_USER', 'postgres')
    password = os.getenv('POSTGRES_PASSWORD', 'postgres')
    return f'host={host} port={port} dbname={db} user={user} password={password}'


def wait_for_db_application_status(application_id: str, expected: str) -> Dict[str, Any]:
    return wait_for(
        f'DB application status {expected}',
        lambda: fetch_db_state(application_id),
        lambda state: state['application'] is not None and state['application']['status'] == expected,
        attempts=40,
    )


def wait_for_notifications(api: API, ctx, application_id: str, notif_type: str, label: str):
    return wait_for(
        label,
        lambda: {'items': notifications(api, ctx)},
        lambda data: find_notification(data['items'], application_id, notif_type) is not None,
        attempts=20,
    )['items']


def assert_db_notification(application_id: str, user_id: str, notif_type: str) -> None:
    with psycopg.connect(pg_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select count(*)
                from notifications
                where application_id = %s and user_id = %s and type = %s
                """,
                (application_id, user_id, notif_type),
            )
            count = cur.fetchone()[0]
            if count < 1:
                raise CheckError(
                    f'DB notification {notif_type} for user {user_id} and application {application_id} is missing'
                )


def fetch_db_state(application_id: str) -> Dict[str, Any]:
    with psycopg.connect(pg_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select id, status, invitation_text, invitation_sent_at, invitation_expires_at,
                       response_received_at, closed_at
                from applications
                where id = %s
                """,
                (application_id,),
            )
            application_row = cur.fetchone()

            cur.execute(
                """
                select id, status, scheduled_at, duration_minutes
                from interviews
                where application_id = %s
                order by created_at asc
                """,
                (application_id,),
            )
            interview_rows = cur.fetchall()

            cur.execute(
                """
                select s.id, s.interview_id, s.status, s.start_at, s.end_at
                from recruiter_schedule_slots s
                join interviews i on i.id = s.interview_id
                where i.application_id = %s
                order by s.created_at asc
                """,
                (application_id,),
            )
            slot_rows = cur.fetchall()

            cur.execute(
                """
                select response_type, message
                from invitation_responses
                where application_id = %s
                order by created_at asc
                """,
                (application_id,),
            )
            response_rows = cur.fetchall()

    return {
        'application': None if application_row is None else {
            'id': str(application_row[0]),
            'status': application_row[1],
            'invitation_text': application_row[2],
            'invitation_sent_at': application_row[3],
            'invitation_expires_at': application_row[4],
            'response_received_at': application_row[5],
            'closed_at': application_row[6],
        },
        'interviews': [
            {
                'id': str(row[0]),
                'status': row[1],
                'scheduled_at': row[2],
                'duration_minutes': row[3],
            }
            for row in interview_rows
        ],
        'schedule_slots': [
            {
                'id': str(row[0]),
                'interview_id': str(row[1]),
                'status': row[2],
                'start_at': row[3],
                'end_at': row[4],
            }
            for row in slot_rows
        ],
        'responses': [
            {
                'response_type': row[0],
                'message': row[1],
            }
            for row in response_rows
        ],
    }


if __name__ == '__main__':
    sys.exit(main())
