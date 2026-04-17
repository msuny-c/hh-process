CREATE OR REPLACE FUNCTION gen_random_uuid() RETURNS uuid AS $$
  SELECT (
    substr(h, 1, 8) || '-' || substr(h, 9, 4) || '-4' || substr(h, 13, 3) || '-' ||
    substr('89ab', 1 + (random() * 4)::int, 1) || substr(h, 17, 3) || '-' || substr(h, 21, 12)
  )::uuid
  FROM (SELECT md5(random()::text || clock_timestamp()::text) AS h) t;
$$ LANGUAGE sql VOLATILE;

CREATE TABLE recruiter_schedule_slots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recruiter_user_id UUID NOT NULL,
    interview_id UUID UNIQUE,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedule_slots_recruiter_start ON recruiter_schedule_slots(recruiter_user_id, start_at);
