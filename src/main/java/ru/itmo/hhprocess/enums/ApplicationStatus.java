package ru.itmo.hhprocess.enums;

public enum ApplicationStatus {

    SCREENING_IN_PROGRESS,
    SCREENING_FAILED,
    ON_RECRUITER_REVIEW,
    REJECTED_BY_RECRUITER,
    INVITED,
    INVITATION_RESPONDED,
    CLOSED_BY_TIMEOUT,
    CLOSED_BY_VACANCY;

    public String toExternalStatus() {
        return name();
    }

    public String toCandidateExternalStatus() {
        if (this == SCREENING_IN_PROGRESS) {
            return "APPLICATION_SUBMITTED";
        }
        return toExternalStatus();
    }

    public boolean isTerminal() {
        return this == SCREENING_FAILED
                || this == REJECTED_BY_RECRUITER
                || this == CLOSED_BY_TIMEOUT
                || this == CLOSED_BY_VACANCY;
    }
}
