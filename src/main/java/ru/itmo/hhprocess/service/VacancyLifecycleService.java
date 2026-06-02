package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import org.springframework.http.HttpStatus;

import java.util.*;

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
    private final ru.itmo.hhprocess.camunda.CamundaWorkflowFacade camundaWorkflowFacade;

    public VacancyResponse closeVacancy(UUID vacancyId, CloseVacancyRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
        vacancyService.ensureOwnership(vacancy, recruiterUser);
        if (vacancy.getStatus() == VacancyStatus.CLOSED) {
            return vacancyMapper.toResponse(vacancy);
        }

        if (!camundaWorkflowFacade.closeVacancy(vacancy, request.getReason())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                    "Camunda vacancy management task is not active");
        }

        vacancy = waitForVacancyClosed(vacancyId);
        waitForApplicationsClosed(vacancyId);
        return vacancyMapper.toResponse(vacancy);
    }

    private VacancyEntity waitForVacancyClosed(UUID vacancyId) {
        for (int attempt = 0; attempt < 24; attempt++) {
            VacancyEntity vacancy = vacancyService.findById(vacancyId);
            if (vacancy.getStatus() == VacancyStatus.CLOSED) {
                return vacancy;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not close vacancy in time");
    }

    private void waitForApplicationsClosed(UUID vacancyId) {
        for (int attempt = 0; attempt < 24; attempt++) {
            if (applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES).isEmpty()) {
                return;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not close active applications in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                    "Interrupted while waiting for Camunda process");
        }
    }
}
