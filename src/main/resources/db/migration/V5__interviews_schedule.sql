CREATE TABLE interviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    vacancy_id UUID NOT NULL REFERENCES vacancies (id) ON DELETE CASCADE,
    candidate_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recruiter_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INT NOT NULL,
    message TEXT,
    cancel_reason TEXT,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recruiter_schedule_slots (
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

CREATE TABLE vacancy_status_history (
    id BIGSERIAL PRIMARY KEY,
    vacancy_id UUID NOT NULL REFERENCES vacancies (id) ON DELETE CASCADE,
    old_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    changed_by_user_id UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_interviews_application_status ON interviews(application_id, status);
CREATE INDEX idx_interviews_vacancy_status ON interviews(vacancy_id, status);
CREATE INDEX idx_schedule_slots_recruiter_start ON recruiter_schedule_slots(recruiter_user_id, start_at);
