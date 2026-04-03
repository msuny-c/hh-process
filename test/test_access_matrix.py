#!/usr/bin/env python3
import sys

from api_test_utils import (
    API,
    ADMIN_EMAIL,
    CheckError,
    admin_session,
    create_vacancy,
    expect_status,
    find_notification,
    future_slot,
    get_candidate_application,
    invite_json,
    mark_notification_read,
    recruiter_session,
    register_candidate,
)


def main() -> int:
    api = API()

    bad_me = api.request('GET', '/api/v1/me', auth=(ADMIN_EMAIL, 'definitely-wrong'), expected=[401])
    expect_status(bad_me, 401, 'invalid basic auth must be rejected')

    recruiter = recruiter_session(api)
    candidate_a = register_candidate(api)
    candidate_b = register_candidate(api)
    admin = admin_session(api)

    candidate_create_vacancy = api.request(
        'POST',
        '/api/v1/recruiters/vacancies',
        auth=candidate_a.auth,
        expected=[403],
        payload={
            'title': 'forbidden vacancy',
            'description': 'desc',
            'required_skills': ['python'],
            'screening_threshold': 1,
        },
    )
    expect_status(candidate_create_vacancy, 403, 'candidate must not create vacancy')

    candidate_schedule = api.request(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=candidate_a.auth,
        expected=[403],
        params={'weekOffset': 0},
    )
    expect_status(candidate_schedule, 403, 'candidate must not view recruiter schedule')

    candidate_admin_job = api.request(
        'POST',
        '/api/v1/admin/jobs/close-expired-invitations',
        auth=candidate_a.auth,
        expected=[403],
    )
    expect_status(candidate_admin_job, 403, 'candidate must not run admin job')

    vacancy = create_vacancy(api, recruiter, 'Access matrix vacancy')

    recruiter_apply = api.request(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=recruiter.auth,
        expected=[403],
        payload={
            'resume_text': 'I have strong experience with Python, Spring Boot, PostgreSQL and Docker.',
            'cover_letter': 'This payload is valid, so the endpoint must fail because of access control only.',
        },
    )
    expect_status(recruiter_apply, 403, 'recruiter must not apply as candidate')

    app_b = api.json(
        'POST',
        f"/api/v1/candidates/vacancies/{vacancy['id']}",
        auth=candidate_b.auth,
        expected=[200, 201],
        payload={
            'resume_text': 'Strong Python, Spring Boot, Docker, PostgreSQL.',
            'cover_letter': 'Please review my application.',
        },
    )

    invite_json(api, recruiter, app_b['application_id'], future_slot(days=10, hour_shift=3), message='Access matrix interview')

    recruiter_respond = api.request(
        'POST',
        f"/api/v1/candidates/applications/{app_b['application_id']}/invitation-response",
        auth=recruiter.auth,
        expected=[403],
        payload={'response_type': 'ACCEPT', 'message': 'not allowed'},
    )
    expect_status(recruiter_respond, 403, 'recruiter must not answer candidate invitation endpoint')

    admin_create_vacancy = api.request(
        'POST',
        '/api/v1/recruiters/vacancies',
        auth=admin.auth,
        expected=[403],
        payload={
            'title': 'admin forbidden vacancy',
            'description': 'desc',
            'required_skills': ['python'],
            'screening_threshold': 1,
        },
    )
    expect_status(admin_create_vacancy, 403, 'admin must not create recruiter vacancy')

    foreign_application = get_candidate_application(api, candidate_a, app_b['application_id'], expected=[403])
    expect_status(foreign_application, 403, 'candidate must not read another candidate application')

    candidate_b_notifications = api.json('GET', '/api/v1/notifications', auth=candidate_b.auth, expected=[200])
    invitation_notification = find_notification(candidate_b_notifications, app_b['application_id'], 'INVITATION')
    if invitation_notification is None:
        raise CheckError(f'invitation notification not found for candidate B: {candidate_b_notifications}')

    foreign_notification = mark_notification_read(api, candidate_a, invitation_notification['id'], expected=[403])
    expect_status(foreign_notification, 403, 'candidate must not mark another user notification as read')

    print('Access matrix scenarios passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
