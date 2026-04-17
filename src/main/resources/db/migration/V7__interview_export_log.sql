CREATE TABLE interview_export_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_id UUID NOT NULL REFERENCES interviews (id) ON DELETE CASCADE,
    export_status VARCHAR(32) NOT NULL,
    eis_reference VARCHAR(255),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_interview_export_log_interview UNIQUE (interview_id)
);

CREATE INDEX idx_interview_export_log_status ON interview_export_log(export_status);
