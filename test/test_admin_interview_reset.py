#!/usr/bin/env python3
import os
import time
from typing import Any, Callable, Dict

import psycopg

from api_test_utils import (
    API,
    CheckError,
    admin_reset_interview,
    admin_session,
    apply_to_vacancy,
    create_vacancy,
    find_notification,
    future_slot,
    get_candidate_application_json,
    get_recruiter_application_json,
    invite_json,
    notifications,
    recruiter_session,
    register_candidate,
)


def main() -> None:
    api = API()
    admin = admin_session(api)
    recruiter = recruiter_session(api)
    candidate = register_candidate(api, prefix_email())

    vacancy = create_vacancy(api, recruiter, f'Admin reset vacancy {int(time.time())}', screening_threshold=1)
    application = apply_to_vacancy(api, candidate, vacancy['id'], 'admin reset')
    application_id = application['application_id']
    wait_for_application_status(application_id, 'ON_RECRUITER_REVIEW')

    invite = invite_json(
        api,
        recruiter,
        application_id,
        future_slot(days=10, hour_shift=7),
        message='Admin reset interview',
    )
    interview_id = invite['interview_id']

    reset = admin_reset_interview(api, admin, interview_id, 'Immediate admin reset')
    if reset.get('status') != 'CANCELLED' or reset.get('application_id') != application_id:
        raise CheckError(f'Bad admin reset response: {reset}')

    candidate_view = wait_for_api_application(api, candidate, application_id, 'IN_PROGRESS')
    recruiter_view = get_recruiter_application_json(api, recruiter, application_id)
    if candidate_view.get('interview') is not None:
        raise CheckError(f'Candidate must not see reset interview: {candidate_view}')
    if recruiter_view.get('interview') is not None:
        raise CheckError(f'Recruiter must not see reset interview: {recruiter_view}')

    db_state = fetch_reset_db_state(application_id, interview_id)
    if db_state['application_status'] != 'ON_RECRUITER_REVIEW':
        raise CheckError(f'Application status was not reset in DB: {db_state}')
    if db_state['interview_status'] != 'CANCELLED':
        raise CheckError(f'Interview was not cancelled in DB: {db_state}')
    if db_state['slot_status'] != 'RELEASED':
        raise CheckError(f'Schedule slot was not released in DB: {db_state}')
    if db_state['candidate_notifications'] < 1 or db_state['recruiter_notifications'] < 1:
        raise CheckError(f'Admin reset notifications missing in DB: {db_state}')

    if not find_notification(notifications(api, candidate), application_id, 'INTERVIEW_CANCELLED'):
        raise CheckError('Candidate admin reset notification missing in API')
    if not find_notification(notifications(api, recruiter), application_id, 'INTERVIEW_CANCELLED'):
        raise CheckError('Recruiter admin reset notification missing in API')

    print('OK admin interview reset completed')


def prefix_email() -> str:
    return f'admin_reset_{int(time.time() * 1000)}@example.com'


def wait_for_application_status(application_id: str, expected: str) -> Dict[str, Any]:
    return wait_for_condition(
        lambda: fetch_application_status(application_id),
        lambda state: state.get('application_status') == expected,
        f'application status {expected}',
    )


def wait_for_api_application(api: API, candidate, application_id: str, expected_status: str) -> Dict[str, Any]:
    return wait_for_condition(
        lambda: get_candidate_application_json(api, candidate, application_id),
        lambda state: state.get('status') == expected_status,
        f'candidate API status {expected_status}',
    )


def wait_for_condition(fetch: Callable[[], Dict[str, Any]], predicate: Callable[[Dict[str, Any]], bool], label: str) -> Dict[str, Any]:
    last = None
    for _ in range(30):
        last = fetch()
        if predicate(last):
            return last
        time.sleep(0.5)
    raise CheckError(f'Timed out waiting for {label}: {last}')


def pg_dsn() -> str:
    return (
        f"host={os.getenv('POSTGRES_HOST', 'localhost')} "
        f"port={os.getenv('POSTGRES_PORT', '5432')} "
        f"dbname={os.getenv('POSTGRES_DB', 'postgres')} "
        f"user={os.getenv('POSTGRES_USER', 'postgres')} "
        f"password={os.getenv('POSTGRES_PASSWORD', 'postgres')} "
        f"options=-csearch_path={os.getenv('POSTGRES_SCHEMA', 'public')}"
    )


def fetch_application_status(application_id: str) -> Dict[str, Any]:
    with psycopg.connect(pg_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute('select status from applications where id = %s', (application_id,))
            row = cur.fetchone()
    return {'application_status': row[0] if row else None}


def fetch_reset_db_state(application_id: str, interview_id: str) -> Dict[str, Any]:
    with psycopg.connect(pg_dsn()) as conn:
        with conn.cursor() as cur:
            cur.execute('select status from applications where id = %s', (application_id,))
            application_status = cur.fetchone()[0]
            cur.execute('select status from interviews where id = %s', (interview_id,))
            interview_status = cur.fetchone()[0]
            cur.execute('select status from recruiter_schedule_slots where interview_id = %s', (interview_id,))
            slot_status = cur.fetchone()[0]
            cur.execute(
                """
                select
                    count(*) filter (where user_id = a.candidate_user_id),
                    count(*) filter (where user_id = v.recruiter_user_id)
                from notifications n
                join applications a on a.id = n.application_id
                join vacancies v on v.id = a.vacancy_id
                where n.application_id = %s and n.type = 'INTERVIEW_CANCELLED'
                """,
                (application_id,),
            )
            candidate_notifications, recruiter_notifications = cur.fetchone()
    return {
        'application_status': application_status,
        'interview_status': interview_status,
        'slot_status': slot_status,
        'candidate_notifications': candidate_notifications,
        'recruiter_notifications': recruiter_notifications,
    }


if __name__ == '__main__':
    main()
