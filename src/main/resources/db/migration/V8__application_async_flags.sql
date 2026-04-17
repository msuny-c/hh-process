ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS screening_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS screening_finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS screening_error TEXT;
