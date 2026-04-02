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
        return switch (this) {
            case SCREENING_IN_PROGRESS, ON_RECRUITER_REVIEW -> "IN_PROGRESS";
            case SCREENING_FAILED, REJECTED_BY_RECRUITER -> "REJECTED";
            case INVITED -> "INVITED";
            case INVITATION_RESPONDED -> "RESPONDED";
            case CLOSED_BY_TIMEOUT, CLOSED_BY_VACANCY -> "CLOSED";
        };
    }

    public boolean isTerminal() {
        return this == SCREENING_FAILED
                || this == REJECTED_BY_RECRUITER
                || this == CLOSED_BY_TIMEOUT
                || this == CLOSED_BY_VACANCY;
    }
}
