#!/usr/bin/env python3
import sys

from api_test_utils import (
    API,
    CheckError,
    create_vacancy,
    filter_schedule_items_for_application,
    find_notification,
    future_slot,
    get_candidate_application_json,
    get_recruiter_application_json,
    invite,
    invite_json,
    notifications,
    recruiter_session,
    register_candidate,
    reject_application,
    schedule_for_week,
)


def assert_failed_invite_does_not_leave_partial_state() -> None:
    api = API()
    recruiter = recruiter_session(api)
    candidate_a = register_candidate(api)
    candidate_b = register_candidate(api)

    vacancy = create_vacancy(api, recruiter, 'Atomicity overlap vacancy')
    app_a = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate_a.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Python Spring PostgreSQL Docker A',
            'cover_letter': 'A',
        },
    )
    app_b = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate_b.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Python Spring PostgreSQL Docker B',
            'cover_letter': 'B',
        },
    )

    scheduled = future_slot(days=12, hour_shift=1)
    invite_a = invite_json(api, recruiter, app_a['application_id'], scheduled, message='Atomicity interview A')
    invite_b_resp = invite(api, recruiter, app_b['application_id'], scheduled, message='Atomicity interview B')

    if invite_b_resp.status_code != 409 or 'SCHEDULE_SLOT_CONFLICT' not in invite_b_resp.text:
        raise CheckError(f'expected schedule conflict for second invite, got {invite_b_resp.status_code}: {invite_b_resp.text}')

    recruiter_view_b = get_recruiter_application_json(api, recruiter, app_b['application_id'])
    if recruiter_view_b['status'] != 'ON_RECRUITER_REVIEW':
        raise CheckError(f'failed invite changed recruiter-visible status: {recruiter_view_b}')
    if recruiter_view_b.get('interview') is not None:
        raise CheckError(f'failed invite left recruiter-visible interview info: {recruiter_view_b}')

    candidate_view_b = get_candidate_application_json(api, candidate_b, app_b['application_id'])
    if candidate_view_b['status'] != 'IN_PROGRESS':
        raise CheckError(f'failed invite changed candidate-visible status: {candidate_view_b}')
    if candidate_view_b.get('invitation') is not None or candidate_view_b.get('interview') is not None:
        raise CheckError(f'failed invite left invitation/interview for candidate: {candidate_view_b}')

    candidate_b_notifications = notifications(api, candidate_b)
    if find_notification(candidate_b_notifications, app_b['application_id'], 'INVITATION') is not None:
        raise CheckError(f'failed invite created candidate notification: {candidate_b_notifications}')

    schedule = schedule_for_week(api, recruiter, scheduled)
    app_a_slots = filter_schedule_items_for_application(schedule, app_a['application_id'])
    app_b_slots = filter_schedule_items_for_application(schedule, app_b['application_id'])
    if not any(item.get('interview_id') == invite_a['interview_id'] and item.get('status') == 'RESERVED' for item in app_a_slots):
        raise CheckError(f'successful invite slot not present in schedule: {schedule}')
    if app_b_slots:
        raise CheckError(f'failed invite left schedule artifacts for second application: {app_b_slots}')


def assert_reject_with_interview_releases_slot() -> None:
    api = API()
    recruiter = recruiter_session(api)
    candidate = register_candidate(api)

    vacancy = create_vacancy(api, recruiter, 'Atomicity reject vacancy')
    app = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Python Spring PostgreSQL Docker reject',
            'cover_letter': 'Reject me after invite',
        },
    )

    scheduled = future_slot(days=14, hour_shift=2)
    invite_data = invite_json(api, recruiter, app['application_id'], scheduled, message='Reject path interview')
    reject_application(api, recruiter, app['application_id'], comment='Reject after invite')

    recruiter_view = get_recruiter_application_json(api, recruiter, app['application_id'])
    if recruiter_view['status'] != 'REJECTED_BY_RECRUITER':
        raise CheckError(f'application must be rejected after composite reject flow: {recruiter_view}')
    if recruiter_view.get('interview') is not None:
        raise CheckError(f'active interview leaked after reject: {recruiter_view}')

    candidate_view = get_candidate_application_json(api, candidate, app['application_id'])
    if candidate_view['status'] != 'REJECTED':
        raise CheckError(f'candidate must see rejected status: {candidate_view}')
    if candidate_view.get('interview') is not None:
        raise CheckError(f'candidate still sees interview after reject: {candidate_view}')

    schedule = schedule_for_week(api, recruiter, scheduled)
    app_slots = filter_schedule_items_for_application(schedule, app['application_id'])
    if not any(item.get('interview_id') == invite_data['interview_id'] and item.get('status') == 'RELEASED' and item.get('interview_status') == 'CANCELLED' for item in app_slots):
        raise CheckError(f'reject must release slot and cancel interview: {app_slots}')

    candidate_notifications = notifications(api, candidate)
    if find_notification(candidate_notifications, app['application_id'], 'APPLICATION_REJECTED') is None:
        raise CheckError(f'candidate did not get rejection notification: {candidate_notifications}')



def main() -> int:
    assert_failed_invite_does_not_leave_partial_state()
    assert_reject_with_interview_releases_slot()
    print('Transaction atomicity scenarios passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
