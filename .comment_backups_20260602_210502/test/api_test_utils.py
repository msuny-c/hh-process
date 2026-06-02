#!/usr/bin/env python3
import json
import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import requests

BASE_URL = os.getenv('BASE_URL', 'http://localhost:8080').rstrip('/')
ADMIN_EMAIL = os.getenv('ADMIN_EMAIL', 'admin@example.com')
ADMIN_PASSWORD = os.getenv('ADMIN_PASSWORD', 'password123')
RECRUITER_EMAIL = os.getenv('RECRUITER_EMAIL', 'recruiter@example.com')
RECRUITER_PASSWORD = os.getenv('RECRUITER_PASSWORD', 'password123')
CANDIDATE_PASSWORD = os.getenv('CANDIDATE_PASSWORD', 'password123')
REQUEST_TIMEOUT = int(os.getenv('REQUEST_TIMEOUT', '30'))
REQUEST_RETRIES = int(os.getenv('REQUEST_RETRIES', '1'))


class CheckError(RuntimeError):
    pass


@dataclass(frozen=True)
class SessionCtx:
    role: str
    auth: Tuple[str, str]
    user_id: str
    email: str


class API:
    def __init__(self, base_url: str = BASE_URL):
        self.base_url = base_url.rstrip('/')
        self.client = requests.Session()
        self.client.headers.update({'Content-Type': 'application/json'})

    def _url(self, path: str) -> str:
        return f'{self.base_url}{path}'

    def request(
        self,
        method: str,
        path: str,
        auth: Optional[Tuple[str, str]] = None,
        expected: Optional[Sequence[int]] = None,
        payload: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
    ) -> requests.Response:
        last_error: Optional[Exception] = None
        for attempt in range(REQUEST_RETRIES + 1):
            try:
                resp = self.client.request(
                    method,
                    self._url(path),
                    auth=auth,
                    data=json.dumps(payload) if payload is not None else None,
                    params=params,
                    timeout=REQUEST_TIMEOUT,
                )
                if expected is not None and resp.status_code not in expected:
                    raise CheckError(
                        f'{method} {path} -> {resp.status_code}, expected {list(expected)}. Body: {resp.text}'
                    )
                return resp
            except requests.exceptions.ReadTimeout as exc:
                last_error = exc
                if attempt >= REQUEST_RETRIES:
                    raise
        raise CheckError(f'Request failed after retries: {last_error}')

    def json(self, *args: Any, **kwargs: Any) -> Any:
        resp = self.request(*args, **kwargs)
        if not resp.content:
            return None
        try:
            return resp.json()
        except Exception as exc:
            raise CheckError(f'Non-JSON response: {resp.text}') from exc


def iso_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


def week_offset_for(dt: datetime) -> int:
    now = datetime.now(timezone.utc)
    current_week_start = (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    target_week_start = (dt - timedelta(days=dt.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((target_week_start - current_week_start).days // 7)


def future_slot(days: int = 7, hour_shift: int = 0) -> datetime:
    base = datetime.now(timezone.utc) + timedelta(days=days, hours=hour_shift)
    return base.replace(minute=0, second=0, microsecond=0)


def unique_email(prefix: str = 'candidate') -> str:
    return f'{prefix}_{uuid.uuid4().hex[:10]}@example.com'


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def expect_status(resp: requests.Response, code: int, message: str) -> None:
    if resp.status_code != code:
        raise CheckError(f'{message}: got {resp.status_code}, body={resp.text}')


def me(api: API, auth: Tuple[str, str], expected_role: str) -> SessionCtx:
    data = api.json('GET', '/api/v1/me', auth=auth, expected=[200])
    roles = data.get('roles') or []
    ensure(expected_role in roles, f'{auth[0]} missing role {expected_role}: {data}')
    return SessionCtx(role=expected_role, auth=auth, user_id=data['user_id'], email=data['email'])


def admin_session(api: API) -> SessionCtx:
    return me(api, (ADMIN_EMAIL, ADMIN_PASSWORD), 'ADMIN')


def recruiter_session(api: API) -> SessionCtx:
    return me(api, (RECRUITER_EMAIL, RECRUITER_PASSWORD), 'RECRUITER')


def register_candidate(api: API, email: Optional[str] = None, password: str = CANDIDATE_PASSWORD) -> SessionCtx:
    actual_email = email or unique_email()
    payload = {
        'email': actual_email,
        'password': password,
        'first_name': 'Ivan',
        'last_name': 'Ivanov',
    }
    data = api.json('POST', '/api/v1/auth/register/candidate', expected=[200, 201], payload=payload)
    ensure(data and data.get('user_id'), f'Bad register response: {data}')
    return me(api, (actual_email, password), 'CANDIDATE')


def create_vacancy(api: API, recruiter: SessionCtx, title: str, screening_threshold: int = 1) -> Dict[str, Any]:
    return api.json(
        'POST',
        '/api/v1/recruiters/vacancies',
        auth=recruiter.auth,
        expected=[200, 201],
        payload={
            'title': title,
            'description': f'Vacancy {title}',
            'required_skills': ['python', 'spring'],
            'screening_threshold': screening_threshold,
        },
    )


def update_vacancy_status(api: API, recruiter: SessionCtx, vacancy_id: str, status: str) -> Dict[str, Any]:
    return api.json(
        'PATCH',
        f'/api/v1/recruiters/vacancies/{vacancy_id}/status',
        auth=recruiter.auth,
        expected=[200],
        payload={'status': status},
    )


def close_vacancy(api: API, recruiter: SessionCtx, vacancy_id: str, reason: str) -> Dict[str, Any]:
    return api.json(
        'POST',
        f'/api/v1/recruiters/vacancies/{vacancy_id}/close',
        auth=recruiter.auth,
        expected=[200],
        payload={'reason': reason},
    )


def apply_to_vacancy(api: API, candidate: SessionCtx, vacancy_id: str, resume_suffix: str = '') -> Dict[str, Any]:
    return api.json(
        'POST',
        f'/api/v1/candidates/vacancies/{vacancy_id}',
        auth=candidate.auth,
        expected=[200, 201],
        payload={
            'resume_text': (
                'I have strong experience with Python, Spring Boot, PostgreSQL and Docker. '
                + resume_suffix
            ).strip(),
            'cover_letter': 'Ready to discuss the role in detail.',
        },
    )


def get_candidate_application(api: API, candidate: SessionCtx, application_id: str, expected: Sequence[int] = (200,)) -> requests.Response:
    return api.request(
        'GET',
        f'/api/v1/candidates/applications/{application_id}',
        auth=candidate.auth,
        expected=expected,
    )


def get_candidate_application_json(api: API, candidate: SessionCtx, application_id: str) -> Dict[str, Any]:
    return api.json('GET', f'/api/v1/candidates/applications/{application_id}', auth=candidate.auth, expected=[200])


def get_recruiter_application_json(api: API, recruiter: SessionCtx, application_id: str) -> Dict[str, Any]:
    return api.json('GET', f'/api/v1/recruiters/applications/{application_id}', auth=recruiter.auth, expected=[200])


def get_recruiter_applications(api: API, recruiter: SessionCtx, vacancy_id: Optional[str] = None) -> List[Dict[str, Any]]:
    params = {'vacancy_id': vacancy_id} if vacancy_id else None
    return api.json('GET', '/api/v1/recruiters/applications', auth=recruiter.auth, expected=[200], params=params)


def invite(api: API, recruiter: SessionCtx, application_id: str, when: datetime, duration_minutes: int = 60, message: str = 'Interview') -> requests.Response:
    return api.request(
        'POST',
        f'/api/v1/recruiters/applications/{application_id}/invite',
        auth=recruiter.auth,
        expected=[200, 409],
        payload={
            'message': message,
            'scheduled_at': iso_utc(when),
            'duration_minutes': duration_minutes,
        },
    )


def invite_json(api: API, recruiter: SessionCtx, application_id: str, when: datetime, duration_minutes: int = 60, message: str = 'Interview') -> Dict[str, Any]:
    resp = invite(api, recruiter, application_id, when, duration_minutes, message)
    if resp.status_code != 200:
        raise CheckError(f'Invite failed: {resp.status_code} {resp.text}')
    return resp.json()


def invite_with_retry(api: API, recruiter: SessionCtx, application_id: str, start_at: datetime, duration_minutes: int = 60, attempts: int = 6) -> Dict[str, Any]:
    candidate_time = start_at
    last_response: Optional[requests.Response] = None
    for _ in range(attempts):
        resp = invite(api, recruiter, application_id, candidate_time, duration_minutes)
        if resp.status_code == 200:
            return resp.json()
        last_response = resp
        if 'SCHEDULE_SLOT_CONFLICT' not in resp.text:
            raise CheckError(f'Invite failed: {resp.status_code} {resp.text}')
        candidate_time += timedelta(hours=2)
    raise CheckError(f'Invite failed after retries: {last_response.text if last_response is not None else "unknown error"}')


def reject_application(api: API, recruiter: SessionCtx, application_id: str, comment: str = 'No fit') -> Dict[str, Any]:
    return api.json(
        'POST',
        f'/api/v1/recruiters/applications/{application_id}/reject',
        auth=recruiter.auth,
        expected=[200],
        payload={'comment': comment},
    )


def respond_to_invitation(api: API, candidate: SessionCtx, application_id: str, response_type: str = 'ACCEPT', message: str = 'Ok') -> requests.Response:
    return api.request(
        'POST',
        f'/api/v1/candidates/applications/{application_id}/invitation-response',
        auth=candidate.auth,
        expected=[200, 409, 403],
        payload={'response_type': response_type, 'message': message},
    )


def cancel_interview(api: API, recruiter: SessionCtx, interview_id: str, reason: str = 'Need to reschedule') -> Dict[str, Any]:
    return api.json(
        'POST',
        f'/api/v1/recruiters/interviews/{interview_id}/cancel',
        auth=recruiter.auth,
        expected=[200],
        payload={'reason': reason},
    )


def notifications(api: API, ctx: SessionCtx) -> List[Dict[str, Any]]:
    return api.json('GET', '/api/v1/notifications', auth=ctx.auth, expected=[200])


def mark_notification_read(api: API, ctx: SessionCtx, notification_id: str, expected: Sequence[int] = (204,)) -> requests.Response:
    return api.request(
        'PATCH',
        f'/api/v1/notifications/{notification_id}/read',
        auth=ctx.auth,
        expected=expected,
    )


def schedule_for_week(api: API, recruiter: SessionCtx, dt: datetime) -> Dict[str, Any]:
    return api.json(
        'GET',
        '/api/v1/recruiters/schedule',
        auth=recruiter.auth,
        expected=[200],
        params={'weekOffset': week_offset_for(dt)},
    )


def run_timeout_job(api: API, admin: SessionCtx) -> Dict[str, Any]:
    return api.json('POST', '/api/v1/admin/jobs/close-expired-invitations', auth=admin.auth, expected=[200])


def find_notification(items: Iterable[Dict[str, Any]], application_id: str, notif_type: str) -> Optional[Dict[str, Any]]:
    for item in items:
        if str(item.get('application_id')) == str(application_id) and item.get('type') == notif_type:
            return item
    return None


def filter_schedule_items_for_application(schedule: Dict[str, Any], application_id: str) -> List[Dict[str, Any]]:
    return [item for item in (schedule.get('items') or []) if str(item.get('application_id')) == str(application_id)]
