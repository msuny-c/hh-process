# -*- coding: utf-8 -*-
import json
import os
import uuid
from datetime import datetime, timedelta, timezone

from odoo import fields, http
from odoo.http import request


def _api_key_ok():
    expected = (os.environ.get("ODOO_EIS_API_KEY") or "").strip()
    if not expected:
        return True
    return request.httprequest.headers.get("X-API-Key", "") == expected


def _json_body():
    raw = request.httprequest.data
    if not raw:
        return {}
    return json.loads(raw.decode("utf-8"))


def _parse_instant(s):
    if not s:
        raise ValueError("scheduledAt is required")
    text = str(s).replace("Z", "+00:00")
    return datetime.fromisoformat(text)


def _json(data, status=200):
    return request.make_response(
        json.dumps(data),
        status=status,
        headers=[("Content-Type", "application/json; charset=utf-8")],
    )


def _default_organizer_user(request_env):
    """Резерв, если в Odoo нет пользователя с email рекрутера."""
    Users = request_env["res.users"].sudo()
    user = Users.search([("login", "=", "admin")], limit=1)
    if not user:
        user = Users.search(
            [("active", "=", True), ("login", "!=", False)], order="id", limit=1
        )
    return user


def _organizer_by_recruiter_email(request_env, recruiter_email):
    """
    Показ встреч в календаре — по user_id / partner. Ищем внутреннего пользователя Odoo
    с тем же email, что и рекрутер в hh-process (логин или email на партнёре).
    """
    if not recruiter_email or not str(recruiter_email).strip():
        return _default_organizer_user(request_env)
    email = str(recruiter_email).strip().lower()
    Users = request_env["res.users"].sudo()
    user = Users.search(
        [
            "|",
            ("login", "=ilike", email),
            ("partner_id.email", "=ilike", email),
        ],
        limit=1,
    )
    if user:
        return user
    return _default_organizer_user(request_env)


def _web_base_url(request_env):
    return (request_env["ir.config_parameter"].sudo().get_param("web.base.url") or "").rstrip(
        "/"
    )


def _eis_videocall_vals(request_env, existing_event=None):
    """
    Тот же формат, что в Odoo: {base}/calendar/join_videocall/{access_token}
    (см. calendar.event.DISCUSS_ROUTE, _set_discuss_videocall_location).
    """
    base = _web_base_url(request_env)
    if existing_event and getattr(existing_event, "access_token", None):
        tok = existing_event.access_token
        loc = (existing_event.videocall_location or "").strip() if base else ""
        if not loc or "join_videocall" not in str(loc):
            if base:
                loc = "%s/calendar/join_videocall/%s" % (base, tok)
            else:
                return {"access_token": tok}
        return {"access_token": tok, "videocall_location": loc}
    if not base:
        return {}
    token = uuid.uuid4().hex
    return {
        "access_token": token,
        "videocall_location": "%s/calendar/join_videocall/%s" % (base, token),
    }


def _interview_event_title(data):
    vac = (data.get("vacancyTitle") or "").strip() or "Собеседование"
    cand = (data.get("candidateName") or "").strip() or "Кандидат"
    title = "Собеседование: %s — %s" % (vac, cand)
    return title[:200]


def _calendar_event_vals(
    request_env, name, start_naive, stop_naive, recruiter_email=None, existing_event=None
):
    vals = {
        "name": name,
        "start": start_naive,
        "stop": stop_naive,
        "description": False,
    }
    vals.update(_eis_videocall_vals(request_env, existing_event))
    org = _organizer_by_recruiter_email(request_env, recruiter_email)
    if org:
        vals["user_id"] = org.id
        if org.partner_id:
            vals["partner_ids"] = [(6, 0, [org.partner_id.id])]
    return vals


class HhEisCalendarController(http.Controller):

    @http.route("/api/v1/calendar/entries", type="http", auth="public", methods=["POST"], csrf=False)
    def eis_create(self, **kwargs):
        if not _api_key_ok():
            return _json({"error": "unauthorized"}, status=401)
        try:
            data = _json_body()
        except (json.JSONDecodeError, ValueError) as e:
            return _json({"error": "invalid json: %s" % e}, status=400)
        interview_id = data.get("interviewId")
        if not interview_id:
            return _json({"error": "interviewId is required"}, status=400)
        try:
            uuid.UUID(str(interview_id))
        except ValueError:
            return _json({"error": "interviewId must be a valid UUID"}, status=400)
        try:
            start = _parse_instant(data.get("scheduledAt"))
        except (ValueError, TypeError) as e:
            return _json({"error": "invalid scheduledAt: %s" % e}, status=400)
        try:
            minutes = int(data.get("durationMinutes", 0))
        except (TypeError, ValueError):
            return _json({"error": "invalid durationMinutes"}, status=400)
        if minutes <= 0:
            return _json({"error": "durationMinutes must be positive"}, status=400)
        if start.tzinfo is None:
            start = start.replace(tzinfo=timezone.utc)
        else:
            start = start.astimezone(timezone.utc)
        stop = start + timedelta(minutes=minutes)
        candidate = str(data.get("candidateId", ""))
        recruiter = str(data.get("recruiterId", ""))
        if not candidate or not recruiter:
            return _json({"error": "candidateId and recruiterId are required"}, status=400)
        ustr = str(interview_id)
        Link = request.env["hh.eis.interview"].sudo()
        Event = request.env["calendar.event"].sudo()
        start_naive = start.replace(tzinfo=None)
        stop_naive = stop.replace(tzinfo=None)
        now = fields.Datetime.now()
        title = _interview_event_title(data)
        recruiter_email = data.get("recruiterEmail") or data.get("recruiter_email")
        existing = Link.search([("interview_uuid", "=", ustr)], limit=1)
        if existing and existing.status == "CANCELLED":
            if existing.event_id:
                existing.event_id.unlink()
            existing.unlink()
            existing = False
        if not existing:
            ev = Event.create(
                _calendar_event_vals(
                    request.env,
                    title,
                    start_naive,
                    stop_naive,
                    recruiter_email=recruiter_email,
                    existing_event=None,
                )
            )
            eref = "ODOO-%s" % ev.id
            link = Link.create(
                {
                    "interview_uuid": ustr,
                    "eis_reference": eref,
                    "event_id": ev.id,
                    "status": "EXPORTED",
                    "scheduled_at": start_naive,
                }
            )
        elif not existing.event_id:
            ev = Event.create(
                _calendar_event_vals(
                    request.env,
                    title,
                    start_naive,
                    stop_naive,
                    recruiter_email=recruiter_email,
                    existing_event=None,
                )
            )
            eref = "ODOO-%s" % ev.id
            link = existing
            link.write(
                {
                    "event_id": ev.id,
                    "eis_reference": eref,
                    "status": "EXPORTED",
                    "scheduled_at": start_naive,
                }
            )
        else:
            ev = existing.event_id
            ev.write(
                _calendar_event_vals(
                    request.env,
                    title,
                    start_naive,
                    stop_naive,
                    recruiter_email=recruiter_email,
                    existing_event=ev,
                )
            )
            link = existing
            link.write({"scheduled_at": start_naive})
        out = {
            "eisReference": link.eis_reference,
            "interviewId": ustr,
            "status": "EXPORTED",
            "scheduledAt": _iso_utc(link.scheduled_at) or _iso_utc(link.event_id.start) or data.get("scheduledAt"),
            "updatedAt": _iso_utc(now) or now.isoformat(),
        }
        return _json(out, status=200)

    @http.route(
        "/api/v1/calendar/entries/<string:interview_uuid_str>/cancel",
        type="http",
        auth="public",
        methods=["POST"],
        csrf=False,
    )
    def eis_cancel(self, interview_uuid_str, **kwargs):
        if not _api_key_ok():
            return _json({"error": "unauthorized"}, status=401)
        try:
            iu = uuid.UUID(str(interview_uuid_str))
        except ValueError:
            return _json({"error": "invalid interview id"}, status=400)
        ustr = str(iu)
        Link = request.env["hh.eis.interview"].sudo()
        row = Link.search([("interview_uuid", "=", ustr)], limit=1)
        if not row:
            eref = f"EIS-{ustr[:8]}"
            return _json(
                {
                    "eisReference": eref,
                    "interviewId": ustr,
                    "status": "CANCELLED",
                    "scheduledAt": None,
                    "updatedAt": _iso_utc(fields.Datetime.now()) or fields.Datetime.now().isoformat(),
                },
                status=200,
            )
        eref = row.eis_reference
        sched = _iso_utc(row.scheduled_at) if row.scheduled_at else _iso_utc(row.event_id.start) if row.event_id else None
        if row.event_id:
            row.event_id.unlink()
        row.write(
            {
                "event_id": False,
                "status": "CANCELLED",
            }
        )
        out = {
            "eisReference": eref,
            "interviewId": ustr,
            "status": "CANCELLED",
            "scheduledAt": sched,
            "updatedAt": _iso_utc(fields.Datetime.now()) or fields.Datetime.now().isoformat(),
        }
        return _json(out, status=200)

    @http.route(
        "/api/v1/calendar/entries/<string:interview_uuid_str>",
        type="http",
        auth="public",
        methods=["GET"],
        csrf=False,
    )
    def eis_get(self, interview_uuid_str, **kwargs):
        if not _api_key_ok():
            return _json({"error": "unauthorized"}, status=401)
        try:
            iu = uuid.UUID(str(interview_uuid_str))
        except ValueError:
            return _json({"error": "invalid interview id"}, status=400)
        ustr = str(iu)
        Link = request.env["hh.eis.interview"].sudo()
        row = Link.search([("interview_uuid", "=", ustr)], limit=1)
        if not row:
            return _json({"error": "not found"}, status=404)
        if row.status == "CANCELLED":
            ssched = _iso_utc(row.scheduled_at) if row.scheduled_at else None
            st = "CANCELLED"
        else:
            st = "EXPORTED" if row.event_id else row.status
            ssched = _iso_utc(row.event_id.start) if row.event_id else _iso_utc(row.scheduled_at)
        out = {
            "eisReference": row.eis_reference,
            "interviewId": ustr,
            "status": st,
            "scheduledAt": ssched,
            "updatedAt": _iso_utc(fields.Datetime.now()) or fields.Datetime.now().isoformat(),
        }
        return _json(out, status=200)


def _iso_utc(value):
    if not value:
        return None
    if isinstance(value, str):
        return value
    if value.tzinfo is None:
        v = value.replace(tzinfo=timezone.utc)
    else:
        v = value.astimezone(timezone.utc)
    return v.replace(microsecond=0).isoformat().replace("+00:00", "Z")
