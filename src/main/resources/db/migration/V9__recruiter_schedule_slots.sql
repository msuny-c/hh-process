CREATE TABLE IF NOT EXISTS recruiter_schedule_slots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recruiter_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    interview_id UUID UNIQUE REFERENCES interviews (id) ON DELETE CASCADE,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_schedule_slots_recruiter_start ON recruiter_schedule_slots(recruiter_user_id, start_at);
