#!/usr/bin/env python3
import sys
from datetime import datetime, timedelta, timezone

from api_test_utils import (
    API,
    CheckError,
    create_vacancy,
    expect_status,
    find_notification,
    future_slot,
    invite,
    invite_json,
    mark_notification_read,
    recruiter_session,
    register_candidate,
    respond_to_invitation,
    update_vacancy_status,
)


def main() -> int:
    api = API()
    recruiter = recruiter_session(api)
    candidate = register_candidate(api)

    duplicate_vacancy = create_vacancy(api, recruiter, 'Business rules duplicate apply vacancy')
    first_app = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{duplicate_vacancy['id']}",
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring PostgreSQL Docker duplicate apply',
            'cover_letter': 'First attempt',
        },
    )
    duplicate_apply = api.request(
        'POST',
        f"/api/v1/candidates/vacancies/{duplicate_vacancy['id']}",
        auth=candidate.auth,
        expected=[409],
        payload={
            'resume_text': 'Strong Python Spring PostgreSQL Docker duplicate apply',
            'cover_letter': 'Second attempt',
        },
    )
    if 'APPLICATION_ALREADY_EXISTS' not in duplicate_apply.text:
        raise CheckError(f'duplicate apply returned unexpected body: {duplicate_apply.text}')

    closed_vacancy = create_vacancy(api, recruiter, 'Business rules closed vacancy')
    update_vacancy_status(api, recruiter, closed_vacancy['id'], 'CLOSED')
    closed_apply = api.request(
        'POST',
        f"/api/v1/candidates/vacancies/{closed_vacancy['id']}",
        auth=candidate.auth,
        expected=[409],
        payload={
            'resume_text': 'Strong Python Spring PostgreSQL Docker closed vacancy',
            'cover_letter': 'Please allow me in',
        },
    )
    if 'VACANCY_NOT_ACTIVE' not in closed_apply.text:
        raise CheckError(f'closed vacancy apply returned unexpected body: {closed_apply.text}')

    invite_vacancy = create_vacancy(api, recruiter, 'Business rules invite vacancy')
    invite_app = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{invite_vacancy['id']}",
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python Spring PostgreSQL Docker invite flow',
            'cover_letter': 'Invite me',
        },
    )

    too_soon = invite(
        api,
        recruiter,
        invite_app['application_id'],
        datetime.now(timezone.utc) + timedelta(minutes=2),
        message='Too soon interview',
    )
    if too_soon.status_code != 409:
        raise CheckError(f'invite too close to now must be 409, got {too_soon.status_code}: {too_soon.text}')

    valid_invite = invite_json(api, recruiter, invite_app['application_id'], future_slot(days=8, hour_shift=5), message='Valid interview')
    reinvite = invite(api, recruiter, invite_app['application_id'], future_slot(days=9, hour_shift=5), message='Second interview')
    if reinvite.status_code != 409:
        raise CheckError(f're-invite must fail with 409, got {reinvite.status_code}: {reinvite.text}')

    first_response = respond_to_invitation(api, candidate, invite_app['application_id'], response_type='ACCEPT', message='Works for me')
    expect_status(first_response, 200, 'first invitation response must succeed')

    second_response = respond_to_invitation(api, candidate, invite_app['application_id'], response_type='ACCEPT', message='Duplicate')
    if second_response.status_code != 409:
        raise CheckError(f'second invitation response must fail with 409, got {second_response.status_code}: {second_response.text}')

    recruiter_schedule_low = api.request(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=recruiter.auth,
        expected=[200],
        params={'weekOffset': -52},
    )
    expect_status(recruiter_schedule_low, 200, 'weekOffset -52 must be accepted')

    recruiter_schedule_high = api.request(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=recruiter.auth,
        expected=[200],
        params={'weekOffset': 52},
    )
    expect_status(recruiter_schedule_high, 200, 'weekOffset 52 must be accepted')

    recruiter_schedule_too_low = api.request(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=recruiter.auth,
        expected=[400],
        params={'weekOffset': -53},
    )
    expect_status(recruiter_schedule_too_low, 400, 'weekOffset -53 must be rejected')

    recruiter_schedule_too_high = api.request(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=recruiter.auth,
        expected=[400],
        params={'weekOffset': 53},
    )
    expect_status(recruiter_schedule_too_high, 400, 'weekOffset 53 must be rejected')

    candidate_notifications = api.json('GET', '/api/v1/notifications', auth=candidate.auth, expected=[200])
    invitation_notification = find_notification(candidate_notifications, invite_app['application_id'], 'INVITATION')
    if invitation_notification is None:
        raise CheckError(f'invitation notification not found after valid invite: {candidate_notifications}')

    first_mark = mark_notification_read(api, candidate, invitation_notification['id'], expected=[204])
    expect_status(first_mark, 204, 'first mark-as-read must succeed')
    second_mark = mark_notification_read(api, candidate, invitation_notification['id'], expected=[204])
    expect_status(second_mark, 204, 'second mark-as-read must remain idempotent')

    print('Business rules scenarios passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
