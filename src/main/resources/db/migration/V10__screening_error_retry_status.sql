DROP INDEX IF EXISTS uq_active_application;

CREATE UNIQUE INDEX uq_active_application
    ON applications (candidate_user_id, vacancy_id)
    WHERE status NOT IN (
        'SCREENING_FAILED',
        'SCREENING_ERROR',
        'REJECTED_BY_RECRUITER',
        'INVITATION_RESPONDED',
        'CLOSED_BY_TIMEOUT'
    );
