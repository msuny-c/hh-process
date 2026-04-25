# -*- coding: utf-8 -*-
from odoo import fields, models


class HhEisInterview(models.Model):
    _name = "hh.eis.interview"
    _description = "Link between HH process interview ID and calendar event"
    _rec_name = "eis_reference"

    interview_uuid = fields.Char(
        string="Interview UUID", required=True, index="btree", copy=False
    )
    eis_reference = fields.Char(
        string="EIS reference", required=True, help="Reference returned to HH process (e.g. ODOO-123)"
    )
    event_id = fields.Many2one(
        "calendar.event",
        string="Calendar event",
        ondelete="cascade",
        required=False,
    )
    status = fields.Selection(
        [("EXPORTED", "Exported"), ("CANCELLED", "Cancelled")],
        string="Status",
        default="EXPORTED",
        required=True,
    )
    scheduled_at = fields.Datetime(string="Start (UTC)")

    _sql_constraints = [
        (
            "interview_uuid_unique",
            "unique(interview_uuid)",
            "This interview UUID is already linked.",
        )
    ]
