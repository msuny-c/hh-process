#!/usr/bin/env python3
import json
import os
import sys
import uuid
import time
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple
import requests
from requests.exceptions import ReadTimeout

BASE_URL = os.getenv('BASE_URL', 'http://localhost:8080').rstrip('/')
RECRUITER_EMAIL = os.getenv('RECRUITER_EMAIL', 'recruiter@example.com')
RECRUITER_PASSWORD = os.getenv('RECRUITER_PASSWORD', 'password123')
CANDIDATE_PASSWORD = os.getenv('CANDIDATE_PASSWORD', 'password123')
REQUEST_TIMEOUT = int(os.getenv('REQUEST_TIMEOUT', '60'))
REQUEST_RETRIES = int(os.getenv('REQUEST_RETRIES', '2'))
ASYNC_WAIT_SECONDS = int(os.getenv('ASYNC_WAIT_SECONDS', '30'))
ASYNC_POLL_INTERVAL_SECONDS = float(os.getenv('ASYNC_POLL_INTERVAL_SECONDS', '1'))

class CheckError(RuntimeError):
    pass

@dataclass
class SessionCtx:
    auth: Tuple[str, str]
    user_id: str
    email: str

class API:
    def __init__(self, base):
        self.base = base
        self.client = requests.Session()
        self.client.headers.update({'Content-Type': 'application/json'})
    def req(self, method, path, auth=None, expected=(200,), payload=None, params=None):
        last_error = None
        for attempt in range(REQUEST_RETRIES + 1):
            try:
                r = self.client.request(method, self.base + path, auth=auth,
                                        data=json.dumps(payload) if payload is not None else None,
                                        params=params, timeout=REQUEST_TIMEOUT)
                if r.status_code not in expected:
                    raise CheckError(f'{method} {path} -> {r.status_code}: {r.text}')
                return r
            except ReadTimeout as e:
                last_error = e
                if attempt >= REQUEST_RETRIES:
                    raise
        raise last_error
    def j(self, *a, **k):
        r = self.req(*a, **k)
        return r.json() if r.content else None

def iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')

def week_offset_for(dt: datetime) -> int:
    now = datetime.now(timezone.utc)
    current_week_start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    target_week_start = (dt - timedelta(days=dt.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((target_week_start - current_week_start).days // 7)

def invite_with_retry(api: API, app_id: str, recruiter: SessionCtx, start_at: datetime, duration_minutes: int, attempts: int = 6):
    scheduled_at = start_at
    last_error = None
    for _ in range(attempts):
        payload = {'message': 'Interview on Monday', 'scheduled_at': iso_utc(scheduled_at), 'duration_minutes': duration_minutes}
        r = api.req('POST', f'/api/v1/recruiters/applications/{app_id}/invite', auth=recruiter.auth, expected=(200, 409), payload=payload)
        if r.status_code == 200:
            return r.json()
        if 'INVALID_APPLICATION_STATE' in r.text:
            deadline = time.time() + ASYNC_WAIT_SECONDS
            while time.time() < deadline:
                view = api.j('GET', f'/api/v1/recruiters/applications/{app_id}', auth=recruiter.auth, expected=(200,))
                if view.get('status') == 'ON_RECRUITER_REVIEW':
                    break
                time.sleep(ASYNC_POLL_INTERVAL_SECONDS)
            continue
        if 'SCHEDULE_SLOT_CONFLICT' not in r.text:
            raise CheckError(f'Invite failed: {r.status_code} {r.text}')
        last_error = CheckError('SCHEDULE_SLOT_CONFLICT')
        scheduled_at = scheduled_at + timedelta(hours=2)
    raise last_error or CheckError('Invite failed after retries')

def me(api, auth):
    data = api.j('GET', '/api/v1/me', auth=auth, expected=(200,))
    return SessionCtx(auth, data['user_id'], data['email'])

def register_candidate(api):
    email = f'candidate_{uuid.uuid4().hex[:8]}@example.com'
    api.j('POST', '/api/v1/auth/register/candidate', expected=(200,201), payload={'email': email, 'password': CANDIDATE_PASSWORD, 'first_name': 'Ivan', 'last_name': 'Ivanov'})
    return me(api, (email, CANDIDATE_PASSWORD))

def main():
    api = API(BASE_URL)
    recruiter = me(api, (RECRUITER_EMAIL, RECRUITER_PASSWORD))
    candidate = register_candidate(api)

    vacancy = api.j('POST', '/api/v1/recruiters/vacancies', auth=recruiter.auth, expected=(200,201), payload={'title': 'Composite TX Vacancy', 'description': 'desc', 'required_skills': ['python'], 'screening_threshold': 1})
    created = api.j('POST', f"/api/v1/candidates/vacancies/{vacancy['id']}", auth=candidate.auth, expected=(200,201), payload={'resume_text': 'I have strong experience with Python, Spring Boot, Docker and PostgreSQL.', 'cover_letter': 'Happy to proceed with the interview process.'})
    app_id = created['application_id']

    base_time = datetime.now(timezone.utc) + timedelta(days=7)
    base_time = base_time.replace(minute=0, second=0, microsecond=0)
    invite = invite_with_retry(api, app_id, recruiter, base_time, 60)
    if not invite.get('interview_id') or not invite.get('schedule_slot_id'):
        raise CheckError(f'invite response missing composite artifacts: {invite}')

    scheduled_at = datetime.fromisoformat(invite.get('scheduled_at', iso_utc(base_time)).replace('Z', '+00:00'))
    week_offset = week_offset_for(scheduled_at)
    week = api.j('GET', '/api/v1/recruiters/schedule', auth=recruiter.auth, expected=(200,), params={'weekOffset': week_offset})
    if not week.get('items'):
        raise CheckError(f'schedule week returned no items: {week}')

    api.j('POST', f"/api/v1/recruiters/interviews/{invite['interview_id']}/cancel", auth=recruiter.auth, expected=(200,), payload={'reason': 'Need to reschedule'})
    details2 = api.j('GET', f'/api/v1/recruiters/applications/{app_id}', auth=recruiter.auth, expected=(200,))
    if details2['status'] != 'ON_RECRUITER_REVIEW':
        raise CheckError(f'application should return to review after cancel: {details2}')

    invite2 = invite_with_retry(api, app_id, recruiter, base_time + timedelta(days=1, hours=1), 45)
    api.j('POST', f"/api/v1/recruiters/vacancies/{vacancy['id']}/close", auth=recruiter.auth, expected=(200,), payload={'reason': 'Position filled'})
    details3 = api.j('GET', f'/api/v1/candidates/applications/{app_id}', auth=candidate.auth, expected=(200,))
    if details3['status'] != 'CLOSED_BY_VACANCY':
        raise CheckError(f'application should be closed after vacancy close: {details3}')
    print('Composite transaction scenarios passed')
    return 0

if __name__ == '__main__':
    sys.exit(main())
