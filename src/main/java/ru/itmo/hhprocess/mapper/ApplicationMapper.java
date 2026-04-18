package ru.itmo.hhprocess.mapper;

import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.recruiter.RecruiterApplicationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;

@Component
public class ApplicationMapper {

    public CandidateApplicationResponse toCandidateResponse(ApplicationEntity a, InterviewEntity interview) {
        CandidateApplicationResponse.CandidateApplicationResponseBuilder builder = CandidateApplicationResponse.builder()
                .applicationId(a.getId())
                .vacancyId(a.getVacancy().getId())
                .status(a.getStatus().toCandidateExternalStatus())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt());

        if (a.getStatus() == ApplicationStatus.INVITED && a.getInvitationText() != null) {
            builder.invitation(CandidateApplicationResponse.InvitationInfo.builder()
                    .message(a.getInvitationText())
                    .expiresAt(a.getInvitationExpiresAt())
                    .build());
        }
        if (interview != null) {
            builder.interview(CandidateApplicationResponse.InterviewInfo.builder()
                    .interviewId(interview.getId())
                    .scheduledAt(interview.getScheduledAt())
                    .durationMinutes(interview.getDurationMinutes())
                    .status(interview.getStatus().name())
                    .build());
        }
        return builder.build();
    }

    public RecruiterApplicationResponse toRecruiterResponse(ApplicationEntity a, ScreeningResultEntity sr, InterviewEntity interview) {
        RecruiterApplicationResponse.RecruiterApplicationResponseBuilder builder = RecruiterApplicationResponse.builder()
                .applicationId(a.getId())
                .vacancyId(a.getVacancy().getId())
                .candidateId(a.getCandidateUser().getId())
                .status(a.getStatus().name())
                .resumeText(a.getResumeText())
                .coverLetter(a.getCoverLetter())
                .createdAt(a.getCreatedAt());
        if (sr != null) {
            builder.screening(RecruiterApplicationResponse.ScreeningInfo.builder()
                    .score(sr.getScore())
                    .passed(sr.isPassed())
                    .matchedSkills(sr.getMatchedSkills())
                    .build());
        }
        if (interview != null) {
            builder.interview(RecruiterApplicationResponse.InterviewInfo.builder()
                    .interviewId(interview.getId())
                    .scheduledAt(interview.getScheduledAt())
                    .durationMinutes(interview.getDurationMinutes())
                    .status(interview.getStatus().name())
                    .build());
        }
        return builder.build();
    }
}
