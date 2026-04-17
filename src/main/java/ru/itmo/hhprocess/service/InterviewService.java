package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.repository.InterviewRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;

    @Transactional(readOnly = true)
    public Optional<InterviewEntity> findActiveByApplicationId(UUID applicationId) {
        return interviewRepository.findByApplicationIdAndStatus(applicationId, InterviewStatus.SCHEDULED);
    }

    @Transactional(readOnly = true)
    public List<InterviewEntity> findActiveByApplicationIds(Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return List.of();
        return interviewRepository.findByApplicationIdInAndStatus(applicationIds, InterviewStatus.SCHEDULED);
    }

    @Transactional(readOnly = true)
    public List<InterviewEntity> findByIds(Collection<UUID> interviewIds) {
        if (interviewIds.isEmpty()) {
            return List.of();
        }
        return interviewRepository.findByIdIn(interviewIds);
    }

    @Transactional(readOnly = true)
    public List<InterviewEntity> findScheduledBetween(Instant from, Instant to) {
        return interviewRepository.findByStatusAndScheduledAtBetween(InterviewStatus.SCHEDULED, from, to);
    }

    @Transactional
    public InterviewEntity createScheduledInterview(ApplicationEntity application, UserEntity recruiterUser,
                                                    Instant scheduledAt, int durationMinutes, String message) {
        return interviewRepository.save(InterviewEntity.builder()
                .application(application)
                .vacancy(application.getVacancy())
                .candidateUser(application.getCandidateUser())
                .recruiterUser(recruiterUser)
                .status(InterviewStatus.SCHEDULED)
                .scheduledAt(scheduledAt)
                .durationMinutes(durationMinutes)
                .message(message)
                .build());
    }

    @Transactional
    public InterviewEntity cancel(InterviewEntity interview, String reason) {
        interview.setStatus(InterviewStatus.CANCELLED);
        interview.setCancelReason(reason);
        interview.setCancelledAt(Instant.now());
        return interview;
    }

    @Transactional
    public InterviewEntity getByIdForUpdate(UUID interviewId) {
        return interviewRepository.findByIdForUpdate(interviewId)
                .orElseThrow(() -> new ru.itmo.hhprocess.exception.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        ru.itmo.hhprocess.enums.ErrorCode.INTERVIEW_NOT_FOUND, "Interview not found"));
    }
}
