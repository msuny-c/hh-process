#!/usr/bin/env python3
import os
import sys
import time

import psycopg

from api_test_utils import (
    API,
    CheckError,
    admin_session,
    create_vacancy,
    filter_schedule_items_for_application,
    find_notification,
    future_slot,
    get_candidate_application_json,
    invite_json,
    notifications,
    recruiter_session,
    register_candidate,
    run_timeout_job,
    schedule_for_week,
)


def pg_dsn() -> str:
    host = os.getenv('POSTGRES_HOST', 'localhost')
    port = os.getenv('POSTGRES_PORT', '5432')
    db = os.getenv('POSTGRES_DB', 'postgres')
    user = os.getenv('POSTGRES_USER', 'postgres')
    password = os.getenv('POSTGRES_PASSWORD', 'postgres')
    return f'host={host} port={port} dbname={db} user={user} password={password}'


def expire_invitation(application_id: str) -> None:
    with psycopg.connect(pg_dsn(), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                update applications
                set invitation_expires_at = now() - interval '1 minute'
                where id = %s
                """,
                (application_id,),
            )
            if cur.rowcount != 1:
                raise CheckError(f'failed to age invitation for application {application_id}')


def fetch_db_debug_state(application_id: str) -> dict:
    with psycopg.connect(pg_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute('select now()')
            db_now = cur.fetchone()[0]

            cur.execute(
                """
                select id, status, invitation_sent_at, invitation_expires_at, response_received_at,
                       closed_at, created_at, updated_at
                from applications
                where id = %s
                """,
                (application_id,),
            )
            application_row = cur.fetchone()

            cur.execute(
                """
                select id, status, scheduled_at, cancelled_at, created_at, updated_at
                from interviews
                where application_id = %s
                order by created_at asc
                """,
                (application_id,),
            )
            interview_rows = cur.fetchall()

            cur.execute(
                """
                select s.id, s.interview_id, s.status, s.start_at, s.end_at, s.released_at, s.created_at, s.updated_at
                from recruiter_schedule_slots s
                join interviews i on i.id = s.interview_id
                where i.application_id = %s
                order by s.created_at asc
                """,
                (application_id,),
            )
            slot_rows = cur.fetchall()

    return {
        'db_now': str(db_now),
        'application': None if application_row is None else {
            'id': str(application_row[0]),
            'status': application_row[1],
            'invitation_sent_at': str(application_row[2]),
            'invitation_expires_at': str(application_row[3]),
            'response_received_at': str(application_row[4]),
            'closed_at': str(application_row[5]),
            'created_at': str(application_row[6]),
            'updated_at': str(application_row[7]),
        },
        'interviews': [
            {
                'id': str(row[0]),
                'status': row[1],
                'scheduled_at': str(row[2]),
                'cancelled_at': str(row[3]),
                'created_at': str(row[4]),
                'updated_at': str(row[5]),
            }
            for row in interview_rows
        ],
        'schedule_slots': [
            {
                'id': str(row[0]),
                'interview_id': str(row[1]),
                'status': row[2],
                'start_at': str(row[3]),
                'end_at': str(row[4]),
                'released_at': str(row[5]),
                'created_at': str(row[6]),
                'updated_at': str(row[7]),
            }
            for row in slot_rows
        ],
    }


def print_db_debug_state(label: str, application_id: str) -> dict:
    state = fetch_db_debug_state(application_id)
    print(f'{label}: {state}', flush=True)
    return state


def main() -> int:
    api = API()
    recruiter = recruiter_session(api)
    admin = admin_session(api)
    candidate = register_candidate(api)

    vacancy = create_vacancy(api, recruiter, 'Timeout fixture vacancy')
    app = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring PostgreSQL Docker timeout',
            'cover_letter': 'Let it expire',
        },
    )

    scheduled = future_slot(days=15, hour_shift=4)
    invite_data = invite_json(api, recruiter, app['application_id'], scheduled, message='Timeout interview')
    expire_invitation(app['application_id'])
    print_db_debug_state('DB state before admin timeout job', app['application_id'])

    # The application also has a background @Scheduled timeout processor. In CI,
    # the scheduled job may close the expired invitation just before the manual
    # admin endpoint is called, in which case closed_count can legitimately be 0.
    # So here we assert the final state, not that this exact HTTP call did the work.
    job_result = run_timeout_job(api, admin)
    print_db_debug_state('DB state after admin timeout job', app['application_id'])

    candidate_view = None
    for _ in range(10):
        candidate_view = get_candidate_application_json(api, candidate, app['application_id'])
        if candidate_view['status'] in ('CLOSED', 'CLOSED_BY_TIMEOUT') and candidate_view.get('interview') is None:
            break
        time.sleep(1)
        job_result = run_timeout_job(api, admin)

    if candidate_view is None or candidate_view['status'] not in ('CLOSED', 'CLOSED_BY_TIMEOUT'):
        final_db_state = print_db_debug_state('DB state at timeout failure', app['application_id'])
        raise CheckError(
            f'timeout job did not close the invitation; last job_result={job_result}, candidate_view={candidate_view}, db_state={final_db_state}'
        )
    if candidate_view.get('interview') is not None:
        raise CheckError(f'timeout closure must remove active interview from candidate view: {candidate_view}')

    candidate_notifications = notifications(api, candidate)
    recruiter_notifications = notifications(api, recruiter)
    if find_notification(candidate_notifications, app['application_id'], 'INVITATION_TIMEOUT') is None:
        raise CheckError(f'candidate timeout notification missing: {candidate_notifications}')
    if find_notification(recruiter_notifications, app['application_id'], 'INVITATION_TIMEOUT') is None:
        raise CheckError(f'recruiter timeout notification missing: {recruiter_notifications}')

    schedule = schedule_for_week(api, recruiter, scheduled)
    app_slots = filter_schedule_items_for_application(schedule, app['application_id'])
    if not any(item.get('interview_id') == invite_data['interview_id'] and item.get('status') == 'RELEASED' and item.get('interview_status') == 'CANCELLED' for item in app_slots):
        raise CheckError(f'timeout job must release slot and cancel interview: {app_slots}')

    print('Timeout job scenarios passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
