#!/usr/bin/env python3
import json
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import requests

BASE_URL = os.getenv('BASE_URL', 'http://localhost:8080').rstrip('/')
ADMIN_EMAIL = os.getenv('ADMIN_EMAIL', 'admin@example.com')
ADMIN_PASSWORD = os.getenv('ADMIN_PASSWORD', 'password123')
RECRUITER_EMAIL = os.getenv('RECRUITER_EMAIL', 'recruiter@example.com')
RECRUITER_PASSWORD = os.getenv('RECRUITER_PASSWORD', 'password123')
CANDIDATE_PASSWORD = os.getenv('CANDIDATE_PASSWORD', 'password123')
REQUEST_TIMEOUT = int(os.getenv('REQUEST_TIMEOUT', '20'))

class CheckError(RuntimeError):
    pass

@dataclass
class SessionCtx:
    role: str
    auth: Tuple[str, str]
    user_id: Optional[str] = None
    email: Optional[str] = None

class API:
    def __init__(self, base_url: str):
        self.base_url = base_url
        self.client = requests.Session()
        self.client.headers.update({"Content-Type": "application/json"})

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def request(self, method: str, path: str, auth: Optional[Tuple[str, str]] = None,
                expected: Optional[List[int]] = None, **kwargs: Any) -> requests.Response:
        resp = self.client.request(method, self._url(path), auth=auth, timeout=REQUEST_TIMEOUT, **kwargs)
        if expected is not None and resp.status_code not in expected:
            raise CheckError(f"{method} {path} -> {resp.status_code}, expected {expected}. Body: {resp.text}")
        return resp

    def json(self, method: str, path: str, auth: Optional[Tuple[str, str]] = None,
             expected: Optional[List[int]] = None, payload: Optional[Dict[str, Any]] = None,
             params: Optional[Dict[str, Any]] = None) -> Any:
        resp = self.request(method, path, auth=auth, expected=expected,
                            data=json.dumps(payload) if payload is not None else None, params=params)
        if not resp.content:
            return None
        try:
            return resp.json()
        except Exception as e:
            raise CheckError(f"{method} {path} returned non-JSON response: {resp.text}") from e

def ok(name: str, details: str = "") -> None:
    print(f"[OK] {name}" + (f" — {details}" if details else ""))

def fail(name: str, details: str) -> None:
    print(f"[FAIL] {name} — {details}")

def register_candidate(api: API, email: str) -> None:
    payload = {"email": email, "password": CANDIDATE_PASSWORD, "first_name": "Ivan", "last_name": "Ivanov"}
    data = api.json('POST', '/api/v1/auth/register/candidate', expected=[200, 201], payload=payload)
    if not data or 'user_id' not in data:
        raise CheckError(f'Bad register response: {data}')

def basic_me(api: API, auth: Tuple[str, str], expected_role: str) -> SessionCtx:
    me = api.json('GET', '/api/v1/me', auth=auth, expected=[200])
    roles = me.get('roles') or []
    if expected_role not in roles:
        raise CheckError(f'{auth[0]} missing role {expected_role}: {me}')
    return SessionCtx(role=expected_role, auth=auth, user_id=me.get('user_id'), email=me.get('email'))

def create_vacancy(api: API, recruiter: SessionCtx, title: str) -> Dict[str, Any]:
    payload = {"title": title, "description": f"Vacancy {title}", "required_skills": ["python"], "screening_threshold": 1}
    return api.json('POST', '/api/v1/recruiters/vacancies', auth=recruiter.auth, expected=[200, 201], payload=payload)

def apply(api: API, candidate: SessionCtx, vacancy_id: str) -> Dict[str, Any]:
    return api.json('POST', f'/api/v1/candidates/vacancies/{vacancy_id}', auth=candidate.auth, expected=[200, 201], payload={"resume_text": "I have strong experience with Python, Spring Boot, PostgreSQL and Docker.", "cover_letter": "Ready to discuss the role in detail."})

def candidate_app(api: API, candidate: SessionCtx, app_id: str) -> Dict[str, Any]:
    return api.json('GET', f'/api/v1/candidates/applications/{app_id}', auth=candidate.auth, expected=[200])

def recruiter_apps(api: API, recruiter: SessionCtx, vacancy_id: str) -> List[Dict[str, Any]]:
    return api.json('GET', '/api/v1/recruiters/applications', auth=recruiter.auth, expected=[200], params={'vacancy_id': vacancy_id})

def invite(api: API, recruiter: SessionCtx, app_id: str, when: str) -> Dict[str, Any]:
    payload = {'message': 'Interview', 'scheduled_at': when, 'duration_minutes': 60}
    resp = api.request('POST', f'/api/v1/recruiters/applications/{app_id}/invite', auth=recruiter.auth, expected=[200, 409], data=json.dumps(payload))
    if resp.status_code == 200:
        return resp.json()
    if 'SCHEDULE_SLOT_CONFLICT' in resp.text:
        raise CheckError('SCHEDULE_SLOT_CONFLICT')
    raise CheckError(f'Invite failed: {resp.status_code} {resp.text}')

def iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')

def week_offset_for(dt: datetime) -> int:
    now = datetime.now(timezone.utc)
    current_week_start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    target_week_start = (dt - timedelta(days=dt.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((target_week_start - current_week_start).days // 7)

def invite_with_retry(api: API, recruiter: SessionCtx, app_id: str, start_at: datetime, attempts: int = 6) -> Dict[str, Any]:
    scheduled_at = start_at
    last_error = None
    for _ in range(attempts):
        try:
            return invite(api, recruiter, app_id, iso_utc(scheduled_at))
        except CheckError as e:
            last_error = e
            if str(e) != 'SCHEDULE_SLOT_CONFLICT':
                raise
            scheduled_at = scheduled_at + timedelta(hours=2)
    raise last_error or CheckError('Invite failed after retries')

def reject(api: API, recruiter: SessionCtx, app_id: str) -> Dict[str, Any]:
    return api.json('POST', f'/api/v1/recruiters/applications/{app_id}/reject', auth=recruiter.auth, expected=[200], payload={'comment': 'No fit'})

def respond(api: API, candidate: SessionCtx, app_id: str) -> Dict[str, Any]:
    return api.json('POST', f'/api/v1/candidates/applications/{app_id}/invitation-response', auth=candidate.auth, expected=[200], payload={'response_type': 'ACCEPT', 'message': 'Ok'})

def notifications(api: API, ctx: SessionCtx) -> List[Dict[str, Any]]:
    return api.json('GET', '/api/v1/notifications', auth=ctx.auth, expected=[200])

def close_expired(api: API, admin: SessionCtx) -> Dict[str, Any]:
    return api.json('POST', '/api/v1/admin/jobs/close-expired-invitations', auth=admin.auth, expected=[200])

def main() -> int:
    api = API(BASE_URL)
    candidate_email = f"candidate_{uuid.uuid4().hex[:8]}@example.com"
    results: List[bool] = []
    state: Dict[str, Any] = {}

    def run_case(title: str, func):
        try:
            func()
            results.append(True)
        except Exception as e:
            results.append(False)
            fail(title, str(e))

    run_case('1. Регистрация кандидата', lambda: (register_candidate(api, candidate_email), ok('1. Регистрация кандидата', candidate_email)))

    def case2():
        state['candidate'] = basic_me(api, (candidate_email, CANDIDATE_PASSWORD), 'CANDIDATE')
        state['recruiter'] = basic_me(api, (RECRUITER_EMAIL, RECRUITER_PASSWORD), 'RECRUITER')
        state['admin'] = basic_me(api, (ADMIN_EMAIL, ADMIN_PASSWORD), 'ADMIN')
        ok('2. Basic auth и /me')
    run_case('2. Basic auth и /me', case2)

    def case3():
        vacancy = create_vacancy(api, state['recruiter'], 'Python Basic Auth Vacancy')
        state['vacancy'] = vacancy
        ok('3. Создание вакансии', vacancy.get('id', ''))
    run_case('3. Создание вакансии', case3)

    def case4():
        app = apply(api, state['candidate'], state['vacancy']['id'])
        state['app'] = app
        apps = recruiter_apps(api, state['recruiter'], state['vacancy']['id'])
        if not any(str(x.get('application_id') or x.get('id')) == str(app['application_id']) for x in apps):
            raise CheckError('Recruiter cannot see candidate application')
        ok('4. Создание заявки и просмотр рекрутером')
    run_case('4. Создание заявки и просмотр рекрутером', case4)

    def case5():
        base_time = datetime.now(timezone.utc) + timedelta(days=7)
        base_time = base_time.replace(minute=0, second=0, microsecond=0)
        resp = invite_with_retry(api, state['recruiter'], state['app']['application_id'], base_time)
        state['invite'] = resp
        state['scheduled_at'] = resp.get('scheduled_at') or iso_utc(base_time)
        app = candidate_app(api, state['candidate'], state['app']['application_id'])
        if app.get('status') not in ['INVITED', 'INTERVIEW_SCHEDULED']:
            raise CheckError(f'Unexpected application status after invite: {app}')
        ok('5. Приглашение на интервью')
    run_case('5. Приглашение на интервью', case5)

    def case6():
        scheduled_at = datetime.fromisoformat(state['scheduled_at'].replace('Z', '+00:00'))
        week_offset = week_offset_for(scheduled_at)
        schedule = api.json('GET', '/api/v1/recruiters/schedule', auth=state['recruiter'].auth, expected=[200], params={'weekOffset': week_offset})
        items = schedule.get('items') or []
        if not items:
            raise CheckError(f'Empty schedule: {schedule}')
        ok('6. Просмотр расписания', str(len(items)))
    run_case('6. Просмотр расписания', case6)

    def case7():
        resp = respond(api, state['candidate'], state['app']['application_id'])
        if not resp.get('application_id'):
            raise CheckError(f'Bad response payload: {resp}')
        ok('7. Ответ на приглашение')
    run_case('7. Ответ на приглашение', case7)

    def case8():
        ns = notifications(api, state['candidate'])
        if not isinstance(ns, list):
            raise CheckError('Notifications are not list')
        ok('8. Просмотр уведомлений')
    run_case('8. Просмотр уведомлений', case8)

    def case9():
        data = close_expired(api, state['admin'])
        if 'closed_count' not in data:
            raise CheckError(f'Bad admin job response: {data}')
        ok('9. Админская джоба')
    run_case('9. Админская джоба', case9)

    passed = sum(results)
    total = len(results)
    print(f"\nИтог: {passed}/{total} проверок успешно")
    return 0 if passed == total else 1

if __name__ == '__main__':
    sys.exit(main())
