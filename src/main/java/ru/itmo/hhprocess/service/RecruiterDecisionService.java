package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.recruiter.RecruiterApplicationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.ApplicationMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecruiterDecisionService {

    private final ApplicationRepository applicationRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final VacancyService vacancyService;
    private final InterviewService interviewService;
    private final ApplicationMapper applicationMapper;

    @Transactional(readOnly = true)
    public List<RecruiterApplicationResponse> getApplications(ApplicationStatus status, UUID vacancyId) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        List<ApplicationEntity> applications;

        if (vacancyId != null) {
            VacancyEntity vacancy = vacancyService.findById(vacancyId);
            vacancyService.ensureOwnership(vacancy, recruiterUser);
            applications = status != null
                    ? applicationRepository.findByRecruiterUserIdAndVacancyIdAndStatus(recruiterUser.getId(), vacancyId, status)
                    : applicationRepository.findByRecruiterUserIdAndVacancyId(recruiterUser.getId(), vacancyId);
        } else if (status != null) {
            applications = applicationRepository.findByRecruiterUserIdAndStatus(recruiterUser.getId(), status);
        } else {
            applications = applicationRepository.findByRecruiterUserId(recruiterUser.getId());
        }

        if (applications.isEmpty()) return List.of();
        Map<UUID, ScreeningResultEntity> screeningMap = screeningResultRepository
                .findByApplicationIdIn(applications.stream().map(ApplicationEntity::getId).toList())
                .stream().collect(Collectors.toMap(sr -> sr.getApplication().getId(), Function.identity()));
        Map<UUID, InterviewEntity> interviews = interviewService.findActiveByApplicationIds(applications.stream().map(ApplicationEntity::getId).toList())
                .stream().collect(Collectors.toMap(i -> i.getApplication().getId(), Function.identity()));

        return applications.stream().map(a -> applicationMapper.toRecruiterResponse(a, screeningMap.get(a.getId()), interviews.get(a.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public RecruiterApplicationResponse getApplication(UUID applicationId) {
        ApplicationEntity application = findAndCheckOwnership(applicationId, vacancyService.getRecruiterUserForCurrentUser());
        return applicationMapper.toRecruiterResponse(
                application,
                screeningResultRepository.findByApplicationId(application.getId()).orElse(null),
                interviewService.findActiveByApplicationId(application.getId()).orElse(null)
        );
    }

    private ApplicationEntity findAndCheckOwnership(UUID applicationId, UserEntity recruiterUser) {
        ApplicationEntity application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Application does not belong to your vacancy");
        }
        return application;
    }
}
