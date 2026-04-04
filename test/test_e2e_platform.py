#!/usr/bin/env python3
import os
import sys
import time
from datetime import timedelta
from typing import Any, Dict, List, Optional

try:
    import psycopg  # type: ignore
except Exception:  # pragma: no cover - optional dependency for timeout scenario
    psycopg = None

from api_test_utils import (
    API,
    CheckError,
    admin_session,
    cancel_interview,
    close_vacancy,
    create_vacancy,
    filter_schedule_items_for_application,
    find_notification,
    get_candidate_application_json,
    get_recruiter_application_json,
    invite,
    invite_json,
    invite_with_retry,
    mark_notification_read,
    notifications,
    recruiter_session,
    register_candidate,
    reject_application,
    respond_to_invitation,
    run_timeout_job,
    schedule_for_week,
    update_vacancy_status,
    future_slot,
)


def step(title: str) -> None:
    print(f'\n=== {title} ===', flush=True)


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def candidate_applications(api: API, candidate) -> List[Dict[str, Any]]:
    return api.json(
        'GET',
        '/api/v1/candidates/applications',
        auth=candidate.auth,
        expected=[200],
    )


def recruiter_vacancies(api: API, recruiter) -> List[Dict[str, Any]]:
    return api.json(
        'GET',
        '/api/v1/recruiters/vacancies',
        auth=recruiter.auth,
        expected=[200],
    )


def recruiter_applications(
    api: API,
    recruiter,
    vacancy_id: Optional[str] = None,
    status: Optional[str] = None,
) -> List[Dict[str, Any]]:
    params: Dict[str, Any] = {}
    if vacancy_id is not None:
        params['vacancy_id'] = vacancy_id
    if status is not None:
        params['status'] = status
    return api.json(
        'GET',
        '/api/v1/recruiters/applications',
        auth=recruiter.auth,
        expected=[200],
        params=params or None,
    )


def first_notification_or_fail(items: List[Dict[str, Any]], application_id: str, notif_type: str) -> Dict[str, Any]:
    notification = find_notification(items, application_id, notif_type)
    if notification is None:
        raise CheckError(
            f'notification {notif_type} not found for application {application_id}; '
            f'available={items}'
        )
    return notification


def assert_application_in_list(items: List[Dict[str, Any]], application_id: str, label: str) -> None:
    ids = {str(item.get('application_id') or item.get('id')) for item in items}
    ensure(str(application_id) in ids, f'{label}: application {application_id} not found in {ids}')


def assert_schedule_item(
    schedule: Dict[str, Any],
    application_id: str,
    *,
    slot_status: str,
    interview_status: Optional[str] = None,
) -> Dict[str, Any]:
    items = filter_schedule_items_for_application(schedule, application_id)
    for item in items:
        if item.get('status') == slot_status and (
            interview_status is None or item.get('interview_status') == interview_status
        ):
            return item
    raise CheckError(
        f'schedule item for application {application_id} with '
        f'status={slot_status} interview_status={interview_status} not found; items={items}'
    )


def pg_dsn() -> str:
    host = os.getenv('POSTGRES_HOST', 'localhost')
    port = os.getenv('POSTGRES_PORT', '5432')
    db = os.getenv('POSTGRES_DB', 'postgres')
    user = os.getenv('POSTGRES_USER', 'postgres')
    password = os.getenv('POSTGRES_PASSWORD', 'postgres')
    return f'host={host} port={port} dbname={db} user={user} password={password}'


def expire_invitation(application_id: str) -> None:
    if psycopg is None:
        raise CheckError('psycopg is not installed, cannot run deterministic timeout scenario')
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
                raise CheckError(f'failed to expire invitation for application {application_id}')


def run_timeout_scenario(api: API, admin, recruiter, candidate) -> None:
    step('Admin timeout job: deterministic expiration flow')

    vacancy = create_vacancy(api, recruiter, 'E2E timeout vacancy')
    app = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker timeout coverage.',
            'cover_letter': 'Please let this invitation expire.',
        },
    )

    scheduled = future_slot(days=18, hour_shift=4)
    invite_data = invite_json(api, recruiter, app['application_id'], scheduled, message='Timeout interview')
    expire_invitation(app['application_id'])

    last_result = run_timeout_job(api, admin)
    candidate_view = None
    for _ in range(10):
        candidate_view = get_candidate_application_json(api, candidate, app['application_id'])
        if candidate_view['status'] in ('CLOSED', 'CLOSED_BY_TIMEOUT') and candidate_view.get('interview') is None:
            break
        time.sleep(1)
        last_result = run_timeout_job(api, admin)

    ensure(candidate_view is not None, 'timeout scenario did not fetch candidate view')
    ensure(
        candidate_view['status'] in ('CLOSED', 'CLOSED_BY_TIMEOUT'),
        f'timeout job did not close application; job_result={last_result}, candidate_view={candidate_view}',
    )
    ensure(candidate_view.get('interview') is None, f'timeout closure must remove interview: {candidate_view}')

    candidate_notifications = notifications(api, candidate)
    recruiter_notifications_list = notifications(api, recruiter)
    first_notification_or_fail(candidate_notifications, app['application_id'], 'INVITATION_TIMEOUT')
    first_notification_or_fail(recruiter_notifications_list, app['application_id'], 'INVITATION_TIMEOUT')

    schedule = schedule_for_week(api, recruiter, scheduled)
    released = assert_schedule_item(
        schedule,
        app['application_id'],
        slot_status='RELEASED',
        interview_status='CANCELLED',
    )
    ensure(
        released.get('interview_id') == invite_data['interview_id'],
        f'timeout job released unexpected schedule item: {released}, invite={invite_data}',
    )

    print(f'[OK] Timeout job closed invitation, released slot and cancelled interview: {app["application_id"]}', flush=True)


def run_timeout_smoke(api: API, admin) -> None:
    step('Admin timeout job: smoke call')
    result = run_timeout_job(api, admin)
    ensure('closedCount' in result, f'admin timeout response must contain closedCount: {result}')
    print(f'[OK] Admin timeout endpoint is reachable: {result}', flush=True)


def main() -> int:
    api = API()

    step('Sessions and identities')
    recruiter = recruiter_session(api)
    admin = admin_session(api)
    candidate_accept = register_candidate(api)
    candidate_cancel = register_candidate(api)
    candidate_reject = register_candidate(api)
    candidate_close = register_candidate(api)

    print(
        '[OK] sessions: '
        f'recruiter={recruiter.email}, admin={admin.email}, '
        f'candidates={[candidate_accept.email, candidate_cancel.email, candidate_reject.email, candidate_close.email]}',
        flush=True,
    )

    step('Recruiter creates vacancies and checks GET /recruiters/vacancies')
    vacancy_accept = create_vacancy(api, recruiter, 'E2E accept vacancy')
    vacancy_cancel = create_vacancy(api, recruiter, 'E2E cancel vacancy')
    vacancy_reject = create_vacancy(api, recruiter, 'E2E reject vacancy')
    vacancy_close_post = create_vacancy(api, recruiter, 'E2E close-post vacancy')
    vacancy_close_patch = create_vacancy(api, recruiter, 'E2E close-patch vacancy')
    vacancy_conflict = create_vacancy(api, recruiter, 'E2E conflict vacancy')

    vacancies = recruiter_vacancies(api, recruiter)
    vacancy_ids = {str(item['id']) for item in vacancies}
    for created in (
        vacancy_accept,
        vacancy_cancel,
        vacancy_reject,
        vacancy_close_post,
        vacancy_close_patch,
        vacancy_conflict,
    ):
        ensure(str(created['id']) in vacancy_ids, f'vacancy not returned by GET /recruiters/vacancies: {created}')
    print(f'[OK] recruiter sees created vacancies: {len(vacancies)} total', flush=True)

    step('Candidates apply to several vacancies and check GET /candidates/applications')
    app_accept = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_accept['id']}",
        auth=candidate_accept.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker accept scenario.',
            'cover_letter': 'Ready for the interview.',
        },
    )
    app_cancel = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_cancel['id']}",
        auth=candidate_cancel.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker cancel scenario.',
            'cover_letter': 'Please schedule me.',
        },
    )
    app_reject = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_reject['id']}",
        auth=candidate_reject.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker reject scenario.',
            'cover_letter': 'I still want a full flow.',
        },
    )
    app_close_post_a = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_close_post['id']}",
        auth=candidate_accept.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker vacancy close A.',
            'cover_letter': 'I am candidate A.',
        },
    )
    app_close_post_b = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_close_post['id']}",
        auth=candidate_close.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker vacancy close B.',
            'cover_letter': 'I am candidate B.',
        },
    )
    app_close_patch = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_close_patch['id']}",
        auth=candidate_cancel.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker vacancy close patch.',
            'cover_letter': 'Please keep me in active state.',
        },
    )
    app_conflict_a = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_conflict['id']}",
        auth=candidate_reject.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker conflict A.',
            'cover_letter': 'Book my slot.',
        },
    )
    app_conflict_b = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy_conflict['id']}",
        auth=candidate_close.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring Boot PostgreSQL Docker conflict B.',
            'cover_letter': 'Try same slot as another candidate.',
        },
    )

    candidate_accept_apps = candidate_applications(api, candidate_accept)
    candidate_cancel_apps = candidate_applications(api, candidate_cancel)
    candidate_reject_apps = candidate_applications(api, candidate_reject)
    assert_application_in_list(candidate_accept_apps, app_accept['application_id'], 'candidate_accept own list')
    assert_application_in_list(candidate_accept_apps, app_close_post_a['application_id'], 'candidate_accept second app')
    assert_application_in_list(candidate_cancel_apps, app_cancel['application_id'], 'candidate_cancel own list')
    assert_application_in_list(candidate_cancel_apps, app_close_patch['application_id'], 'candidate_cancel patch-close app')
    assert_application_in_list(candidate_reject_apps, app_reject['application_id'], 'candidate_reject own list')
    assert_application_in_list(candidate_reject_apps, app_conflict_a['application_id'], 'candidate_reject conflict app')
    print('[OK] GET /candidates/applications returns each candidate own applications', flush=True)

    step('Recruiter checks GET /recruiters/applications list and detail')
    all_recruiter_apps = recruiter_applications(api, recruiter)
    by_vacancy_apps = recruiter_applications(api, recruiter, vacancy_id=vacancy_accept['id'])
    assert_application_in_list(all_recruiter_apps, app_accept['application_id'], 'recruiter all applications')
    assert_application_in_list(by_vacancy_apps, app_accept['application_id'], 'recruiter vacancy-filtered applications')
    recruiter_accept_before = get_recruiter_application_json(api, recruiter, app_accept['application_id'])
    ensure(
        recruiter_accept_before['status'] == 'ON_RECRUITER_REVIEW',
        f'new application must be visible as ON_RECRUITER_REVIEW: {recruiter_accept_before}',
    )
    print('[OK] recruiter can list and open applications', flush=True)

    step('Composite transaction: invite candidate to interview')
    accept_slot = future_slot(days=8, hour_shift=2)
    accept_invite = invite_with_retry(api, recruiter, app_accept['application_id'], accept_slot)
    ensure(accept_invite.get('interview_id'), f'invite must create interview: {accept_invite}')
    ensure(accept_invite.get('schedule_slot_id'), f'invite must reserve schedule slot: {accept_invite}')

    candidate_accept_view = get_candidate_application_json(api, candidate_accept, app_accept['application_id'])
    recruiter_accept_view = get_recruiter_application_json(api, recruiter, app_accept['application_id'])
    ensure(candidate_accept_view['status'] == 'INVITED', f'candidate must see INVITED after invite: {candidate_accept_view}')
    ensure(recruiter_accept_view['status'] == 'INVITED', f'recruiter must see INVITED after invite: {recruiter_accept_view}')
    ensure(candidate_accept_view.get('interview') is not None, f'candidate must see interview after invite: {candidate_accept_view}')
    ensure(recruiter_accept_view.get('interview') is not None, f'recruiter must see interview after invite: {recruiter_accept_view}')

    invited_apps = recruiter_applications(api, recruiter, status='INVITED')
    assert_application_in_list(invited_apps, app_accept['application_id'], 'recruiter status=INVITED filter')

    accept_schedule = schedule_for_week(api, recruiter, accept_slot)
    accept_slot_item = assert_schedule_item(
        accept_schedule,
        app_accept['application_id'],
        slot_status='RESERVED',
        interview_status='SCHEDULED',
    )
    ensure(
        accept_slot_item.get('interview_id') == accept_invite['interview_id'],
        f'invite must reserve the created interview slot: slot={accept_slot_item}, invite={accept_invite}',
    )

    accept_candidate_notifications = notifications(api, candidate_accept)
    accept_invitation_notification = first_notification_or_fail(
        accept_candidate_notifications,
        app_accept['application_id'],
        'INVITATION',
    )
    mark_notification_read(api, candidate_accept, accept_invitation_notification['id'], expected=[204])
    print('[OK] invite flow changed status, created interview, reserved slot and created candidate notification', flush=True)

    step('Candidate responds to invitation')
    accept_response = respond_to_invitation(
        api,
        candidate_accept,
        app_accept['application_id'],
        response_type='ACCEPT',
        message='This time works for me.',
    )
    ensure(accept_response.status_code == 200, f'invitation response must succeed: {accept_response.status_code} {accept_response.text}')

    candidate_accept_after_response = get_candidate_application_json(api, candidate_accept, app_accept['application_id'])
    recruiter_accept_after_response = get_recruiter_application_json(api, recruiter, app_accept['application_id'])
    ensure(
        candidate_accept_after_response['status'] == 'RESPONDED',
        f'candidate must see RESPONDED after answer: {candidate_accept_after_response}',
    )
    ensure(
        recruiter_accept_after_response['status'] == 'INVITATION_RESPONDED',
        f'recruiter must see INVITATION_RESPONDED after answer: {recruiter_accept_after_response}',
    )

    recruiter_notifications_list = notifications(api, recruiter)
    response_notification = first_notification_or_fail(
        recruiter_notifications_list,
        app_accept['application_id'],
        'INVITATION_RESPONSE',
    )
    mark_notification_read(api, recruiter, response_notification['id'], expected=[204])
    print('[OK] candidate response updates application and notifies recruiter', flush=True)

    step('Cancel interview endpoint')
    cancel_slot = future_slot(days=9, hour_shift=3)
    cancel_invite = invite_with_retry(api, recruiter, app_cancel['application_id'], cancel_slot)
    cancel_interview(api, recruiter, cancel_invite['interview_id'], reason='Need to reschedule the meeting')

    candidate_cancel_view = get_candidate_application_json(api, candidate_cancel, app_cancel['application_id'])
    recruiter_cancel_view = get_recruiter_application_json(api, recruiter, app_cancel['application_id'])
    ensure(candidate_cancel_view['status'] == 'IN_PROGRESS', f'candidate must return to IN_PROGRESS after cancel: {candidate_cancel_view}')
    ensure(recruiter_cancel_view['status'] == 'ON_RECRUITER_REVIEW', f'recruiter must return to review after cancel: {recruiter_cancel_view}')
    ensure(candidate_cancel_view.get('interview') is None, f'candidate must not see active interview after cancel: {candidate_cancel_view}')
    ensure(recruiter_cancel_view.get('interview') is None, f'recruiter must not see active interview after cancel: {recruiter_cancel_view}')

    cancel_schedule = schedule_for_week(api, recruiter, cancel_slot)
    released_cancel_slot = assert_schedule_item(
        cancel_schedule,
        app_cancel['application_id'],
        slot_status='RELEASED',
        interview_status='CANCELLED',
    )
    ensure(
        released_cancel_slot.get('interview_id') == cancel_invite['interview_id'],
        f'cancel endpoint must release the right slot: {released_cancel_slot}',
    )
    first_notification_or_fail(notifications(api, candidate_cancel), app_cancel['application_id'], 'INTERVIEW_CANCELLED')
    print('[OK] cancel endpoint cancels interview, releases slot and notifies candidate', flush=True)

    step('Composite transaction: reject application with active interview')
    reject_slot = future_slot(days=10, hour_shift=4)
    reject_invite = invite_with_retry(api, recruiter, app_reject['application_id'], reject_slot)
    reject_application(api, recruiter, app_reject['application_id'], comment='We decided to reject this application')

    candidate_reject_view = get_candidate_application_json(api, candidate_reject, app_reject['application_id'])
    recruiter_reject_view = get_recruiter_application_json(api, recruiter, app_reject['application_id'])
    ensure(candidate_reject_view['status'] == 'REJECTED', f'candidate must see REJECTED after reject flow: {candidate_reject_view}')
    ensure(recruiter_reject_view['status'] == 'REJECTED_BY_RECRUITER', f'recruiter must see rejected internal status: {recruiter_reject_view}')
    ensure(candidate_reject_view.get('interview') is None, f'candidate must not see interview after reject flow: {candidate_reject_view}')
    ensure(recruiter_reject_view.get('interview') is None, f'recruiter must not see interview after reject flow: {recruiter_reject_view}')

    reject_schedule = schedule_for_week(api, recruiter, reject_slot)
    released_reject_slot = assert_schedule_item(
        reject_schedule,
        app_reject['application_id'],
        slot_status='RELEASED',
        interview_status='CANCELLED',
    )
    ensure(
        released_reject_slot.get('interview_id') == reject_invite['interview_id'],
        f'reject flow must release the right slot: {released_reject_slot}',
    )
    first_notification_or_fail(notifications(api, candidate_reject), app_reject['application_id'], 'APPLICATION_REJECTED')
    print('[OK] reject flow cancels interview, frees slot and notifies candidate', flush=True)

    step('Composite transaction: close vacancy via POST /close')
    close_post_slot_a = future_slot(days=11, hour_shift=1)
    close_post_slot_b = future_slot(days=11, hour_shift=3)
    close_post_invite_a = invite_with_retry(api, recruiter, app_close_post_a['application_id'], close_post_slot_a)
    close_post_invite_b = invite_with_retry(api, recruiter, app_close_post_b['application_id'], close_post_slot_b)

    close_post_response = respond_to_invitation(
        api,
        candidate_accept,
        app_close_post_a['application_id'],
        response_type='ACCEPT',
        message='I confirm the interview.',
    )
    ensure(close_post_response.status_code == 200, f'pre-close response must succeed: {close_post_response.status_code} {close_post_response.text}')

    close_post_vacancy_response = close_vacancy(api, recruiter, vacancy_close_post['id'], 'Position was filled')
    ensure(close_post_vacancy_response['status'] == 'CLOSED', f'close vacancy must return CLOSED: {close_post_vacancy_response}')

    close_post_candidate_a = get_candidate_application_json(api, candidate_accept, app_close_post_a['application_id'])
    close_post_candidate_b = get_candidate_application_json(api, candidate_close, app_close_post_b['application_id'])
    ensure(close_post_candidate_a['status'] == 'CLOSED', f'close vacancy must close active responded application: {close_post_candidate_a}')
    ensure(close_post_candidate_b['status'] == 'CLOSED', f'close vacancy must close invited application: {close_post_candidate_b}')
    ensure(close_post_candidate_a.get('interview') is None, f'candidate A interview must disappear after vacancy close: {close_post_candidate_a}')
    ensure(close_post_candidate_b.get('interview') is None, f'candidate B interview must disappear after vacancy close: {close_post_candidate_b}')

    close_post_schedule_a = schedule_for_week(api, recruiter, close_post_slot_a)
    close_post_schedule_b = schedule_for_week(api, recruiter, close_post_slot_b)
    released_close_post_a = assert_schedule_item(
        close_post_schedule_a,
        app_close_post_a['application_id'],
        slot_status='RELEASED',
        interview_status='CANCELLED',
    )
    released_close_post_b = assert_schedule_item(
        close_post_schedule_b,
        app_close_post_b['application_id'],
        slot_status='RELEASED',
        interview_status='CANCELLED',
    )
    ensure(released_close_post_a.get('interview_id') == close_post_invite_a['interview_id'], f'wrong released slot for candidate A: {released_close_post_a}')
    ensure(released_close_post_b.get('interview_id') == close_post_invite_b['interview_id'], f'wrong released slot for candidate B: {released_close_post_b}')

    first_notification_or_fail(notifications(api, candidate_accept), app_close_post_a['application_id'], 'VACANCY_CLOSED')
    first_notification_or_fail(notifications(api, candidate_close), app_close_post_b['application_id'], 'VACANCY_CLOSED')
    print('[OK] POST /close closes vacancy, closes active applications, cancels interviews and notifies candidates', flush=True)

    step('Composite transaction: close vacancy via PATCH /status')
    patch_close_view_before = get_candidate_application_json(api, candidate_cancel, app_close_patch['application_id'])
    ensure(patch_close_view_before['status'] == 'IN_PROGRESS', f'patch-close scenario must start active: {patch_close_view_before}')

    patch_close_response = update_vacancy_status(api, recruiter, vacancy_close_patch['id'], 'CLOSED')
    ensure(patch_close_response['status'] == 'CLOSED', f'PATCH /status must close vacancy: {patch_close_response}')

    patch_close_candidate_view = get_candidate_application_json(api, candidate_cancel, app_close_patch['application_id'])
    ensure(patch_close_candidate_view['status'] == 'CLOSED', f'PATCH close must close active application: {patch_close_candidate_view}')
    first_notification_or_fail(notifications(api, candidate_cancel), app_close_patch['application_id'], 'VACANCY_CLOSED')
    print('[OK] PATCH /status=CLOSED routes through vacancy close transaction', flush=True)

    step('Atomicity: failed invite with slot conflict leaves no partial state')
    conflict_slot = future_slot(days=12, hour_shift=2)
    conflict_invite_a = invite_with_retry(api, recruiter, app_conflict_a['application_id'], conflict_slot)
    conflict_invite_b_response = invite(
        api,
        recruiter,
        app_conflict_b['application_id'],
        conflict_slot,
        message='This invite should conflict',
    )
    ensure(
        conflict_invite_b_response.status_code == 409 and 'SCHEDULE_SLOT_CONFLICT' in conflict_invite_b_response.text,
        f'second invite must fail with schedule conflict: {conflict_invite_b_response.status_code} {conflict_invite_b_response.text}',
    )

    recruiter_conflict_b = get_recruiter_application_json(api, recruiter, app_conflict_b['application_id'])
    candidate_conflict_b = get_candidate_application_json(api, candidate_close, app_conflict_b['application_id'])
    ensure(recruiter_conflict_b['status'] == 'ON_RECRUITER_REVIEW', f'failed invite must not change recruiter-visible status: {recruiter_conflict_b}')
    ensure(candidate_conflict_b['status'] == 'IN_PROGRESS', f'failed invite must not change candidate-visible status: {candidate_conflict_b}')
    ensure(recruiter_conflict_b.get('interview') is None, f'failed invite must not leave recruiter interview: {recruiter_conflict_b}')
    ensure(candidate_conflict_b.get('interview') is None, f'failed invite must not leave candidate interview: {candidate_conflict_b}')
    ensure(candidate_conflict_b.get('invitation') is None, f'failed invite must not leave candidate invitation: {candidate_conflict_b}')

    conflict_candidate_notifications = notifications(api, candidate_close)
    ensure(
        find_notification(conflict_candidate_notifications, app_conflict_b['application_id'], 'INVITATION') is None,
        f'failed invite must not create candidate notification: {conflict_candidate_notifications}',
    )

    conflict_schedule = schedule_for_week(api, recruiter, conflict_slot)
    reserved_conflict_slot = assert_schedule_item(
        conflict_schedule,
        app_conflict_a['application_id'],
        slot_status='RESERVED',
        interview_status='SCHEDULED',
    )
    ensure(
        reserved_conflict_slot.get('interview_id') == conflict_invite_a['interview_id'],
        f'first invite must still own the slot: {reserved_conflict_slot}',
    )
    ensure(
        not filter_schedule_items_for_application(conflict_schedule, app_conflict_b['application_id']),
        f'failed invite must not leave schedule artifacts for second application: {conflict_schedule}',
    )
    print('[OK] slot-conflict invite keeps transaction atomic for the losing application', flush=True)

    if psycopg is not None:
        run_timeout_scenario(api, admin, recruiter, candidate_close)
    else:
        run_timeout_smoke(api, admin)
        print('[INFO] psycopg is unavailable, so deterministic timeout aging was skipped.', flush=True)

    print('\nE2E platform scenario passed', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
