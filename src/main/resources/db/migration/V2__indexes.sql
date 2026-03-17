CREATE INDEX idx_applications_vacancy_id            ON applications (vacancy_id);
CREATE INDEX idx_applications_candidate_user_id      ON applications (candidate_user_id);
CREATE INDEX idx_applications_status                 ON applications (status);
CREATE INDEX idx_applications_invitation_expires_at   ON applications (invitation_expires_at);
CREATE INDEX idx_notifications_user_id               ON notifications (user_id);
CREATE INDEX idx_application_status_history_app_id   ON application_status_history (application_id);

CREATE UNIQUE INDEX uq_active_application
    ON applications (candidate_user_id, vacancy_id)
    WHERE status NOT IN ('SCREENING_FAILED', 'REJECTED_BY_RECRUITER', 'INVITATION_RESPONDED', 'CLOSED_BY_TIMEOUT');
