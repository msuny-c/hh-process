package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VacancyLifecycleService {

    private static final List<ApplicationStatus> ACTIVE_APPLICATION_STATUSES = List.of(
            ApplicationStatus.SCREENING_IN_PROGRESS,
            ApplicationStatus.ON_RECRUITER_REVIEW,
            ApplicationStatus.INVITED,
            ApplicationStatus.INVITATION_RESPONDED
    );

    private final VacancyService vacancyService;
    private final ApplicationRepository applicationRepository;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final HistoryService historyService;
    private final VacancyHistoryService vacancyHistoryService;
    private final NotificationService notificationService;
    private final VacancyMapper vacancyMapper;

    @Transactional
    public VacancyResponse closeVacancy(UUID vacancyId, CloseVacancyRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
        vacancyService.ensureOwnership(vacancy, recruiterUser);
        VacancyStatus oldStatus = vacancy.getStatus();
        vacancy.setStatus(VacancyStatus.CLOSED);
        vacancyHistoryService.record(vacancy, oldStatus, VacancyStatus.CLOSED, recruiterUser);

        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES);
        Map<UUID, InterviewEntity> activeInterviews = interviewService.findActiveByApplicationIds(
                applications.stream().map(ApplicationEntity::getId).toList())
                .stream().collect(Collectors.toMap(i -> i.getApplication().getId(), Function.identity()));

        Instant now = Instant.now();
        for (ApplicationEntity application : applications) {
            ApplicationStatus oldAppStatus = application.getStatus();
            InterviewEntity interview = activeInterviews.get(application.getId());
            if (interview != null) {
                interview.setStatus(ru.itmo.hhprocess.enums.InterviewStatus.CANCELLED);
                interview.setCancelReason(request.getReason());
                interview.setCancelledAt(now);
                scheduleService.releaseForInterview(interview);
            }
            application.setStatus(ApplicationStatus.CLOSED_BY_VACANCY);
            application.setClosedAt(now);
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(request.getReason());
            historyService.record(application, oldAppStatus, ApplicationStatus.CLOSED_BY_VACANCY, recruiterUser);
            notificationService.create(application.getCandidateUser(), application, NotificationType.VACANCY_CLOSED,
                    "Vacancy was closed: " + vacancy.getTitle());
        }
        return vacancyMapper.toResponse(vacancy);
    }
}
