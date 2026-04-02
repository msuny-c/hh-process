#!/usr/bin/env python3
import json
import os
import sys
import uuid
import requests

BASE_URL = os.getenv('BASE_URL', 'http://localhost:8080').rstrip('/')
RECRUITER = ('recruiter@example.com', 'password123')
ADMIN = ('admin@example.com', 'password123')
CANDIDATE_PASSWORD = 'password123'


def req(method, path, auth=None, expected=(200,), payload=None, params=None):
    r = requests.request(method, BASE_URL + path, auth=auth, json=payload, params=params, timeout=20)
    if r.status_code not in expected:
        raise RuntimeError(f'{method} {path} -> {r.status_code}: {r.text}')
    return r


def main():
    bad = req('GET', '/api/v1/recruiters/vacancies', expected=(401,))
    if bad.status_code != 401:
        raise RuntimeError('anonymous call should be 401')

    email = f'candidate_{uuid.uuid4().hex[:8]}@example.com'
    req('POST', '/api/v1/auth/register/candidate', expected=(200,201), payload={'email': email, 'password': CANDIDATE_PASSWORD, 'first_name': 'Ivan', 'last_name': 'Ivanov'})
    candidate = (email, CANDIDATE_PASSWORD)

    forbidden = req('POST', '/api/v1/admin/jobs/close-expired-invitations', auth=candidate, expected=(403,))
    if forbidden.status_code != 403:
        raise RuntimeError('candidate should not run admin job')

    validation = req('POST', '/api/v1/recruiters/vacancies', auth=RECRUITER, expected=(400,), payload={'title': '   ', 'description': 'x', 'required_skills': ['python'], 'screening_threshold': 101})
    if validation.status_code != 400:
        raise RuntimeError('invalid vacancy payload should be 400')

    vacancy = req('POST', '/api/v1/recruiters/vacancies', auth=RECRUITER, expected=(200,201), payload={'title': 'Order test', 'description': 'desc', 'required_skills': ['python'], 'screening_threshold': 1}).json()
    app = req('POST', f"/api/v1/candidates/vacancies/{vacancy['id']}", auth=candidate, expected=(200,201), payload={'resume_text': 'I have solid experience with Python, Spring Boot and PostgreSQL.', 'cover_letter': 'hello there'}).json()

    wrong_sequence = req('POST', f"/api/v1/candidates/applications/{app['application_id']}/invitation-response", auth=candidate, expected=(409,), payload={'response_type': 'ACCEPT', 'message': 'ok'})
    if wrong_sequence.status_code != 409:
        raise RuntimeError('respond before invite should be 409')

    bad_week = req('GET', '/api/v1/recruiters/schedule', auth=RECRUITER, expected=(400,), params={'weekOffset': 999})
    if bad_week.status_code != 400:
        raise RuntimeError('weekOffset validation should be 400')

    print('Security/validation scenarios passed')
    return 0

if __name__ == '__main__':
    sys.exit(main())
