CREATE OR REPLACE FUNCTION gen_random_uuid() RETURNS uuid AS $$
  SELECT (
    substr(h, 1, 8) || '-' || substr(h, 9, 4) || '-4' || substr(h, 13, 3) || '-' ||
    substr('89ab', 1 + (random() * 4)::int, 1) || substr(h, 17, 3) || '-' || substr(h, 21, 12)
  )::uuid
  FROM (SELECT md5(random()::text || clock_timestamp()::text) AS h) t;
$$ LANGUAGE sql VOLATILE;

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255)        NOT NULL,
    role          VARCHAR(32)         NOT NULL,
    enabled       BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ         NOT NULL DEFAULT now()
);

CREATE TABLE candidates (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID UNIQUE NOT NULL REFERENCES users (id),
    full_name  VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE recruiters (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID UNIQUE NOT NULL REFERENCES users (id),
    full_name  VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE vacancies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recruiter_id        UUID         NOT NULL REFERENCES recruiters (id),
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    status              VARCHAR(32)  NOT NULL,
    required_skills     JSONB        NOT NULL DEFAULT '[]',
    screening_threshold INT          NOT NULL DEFAULT 75,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE applications (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vacancy_id            UUID         NOT NULL REFERENCES vacancies (id),
    candidate_id          UUID         NOT NULL REFERENCES candidates (id),
    resume_text           TEXT         NOT NULL,
    cover_letter          TEXT,
    status                VARCHAR(32)  NOT NULL,
    recruiter_comment     TEXT,
    invitation_text       TEXT,
    invitation_sent_at    TIMESTAMPTZ,
    invitation_expires_at TIMESTAMPTZ,
    response_received_at  TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE screening_results (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID UNIQUE NOT NULL REFERENCES applications (id),
    score          INT         NOT NULL,
    passed         BOOLEAN     NOT NULL,
    matched_skills JSONB,
    details_json   JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invitation_responses (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID UNIQUE NOT NULL REFERENCES applications (id),
    candidate_id   UUID        NOT NULL REFERENCES candidates (id),
    response_type  VARCHAR(32) NOT NULL,
    message        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id),
    application_id UUID                 REFERENCES applications (id),
    type           VARCHAR(64) NOT NULL,
    message        TEXT        NOT NULL,
    is_read        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_status_history (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id     UUID        NOT NULL REFERENCES applications (id),
    old_status         VARCHAR(32),
    new_status         VARCHAR(32) NOT NULL,
    reason_code        VARCHAR(64),
    reason_text        TEXT,
    changed_by_user_id UUID                 REFERENCES users (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
