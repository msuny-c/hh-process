package com.example.hhprocess.mapper;

import com.example.hhprocess.dto.ApplicationHistoryResponse;
import com.example.hhprocess.dto.ApplicationResponse;
import com.example.hhprocess.entity.ApplicationStatusHistory;
import com.example.hhprocess.entity.JobApplication;
import org.springframework.stereotype.Component;

@Component
public class ApplicationMapper {
    public ApplicationResponse toResponse(JobApplication application) {
        return ApplicationResponse.builder()
                .id(application.getId())
                .vacancyId(application.getVacancy().getId())
                .vacancyTitle(application.getVacancy().getTitle())
                .candidateId(application.getCandidate().getId())
                .candidateFullName(application.getCandidate().getFullName())
                .candidateEmail(application.getCandidate().getEmail())
                .coverLetter(application.getCoverLetter())
                .status(application.getStatus())
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                .build();
    }

    public ApplicationHistoryResponse toHistoryResponse(ApplicationStatusHistory history) {
        return ApplicationHistoryResponse.builder()
                .id(history.getId())
                .oldStatus(history.getOldStatus())
                .newStatus(history.getNewStatus())
                .changedBy(history.getChangedBy())
                .comment(history.getComment())
                .changedAt(history.getChangedAt())
                .build();
    }
}
