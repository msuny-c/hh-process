package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StatusTransitionService {

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;

    public Map<String, Object> prepareStatusTransition(String currentStatus, String action, String requestedStatus) {
        return Map.of(
                "currentStatus", currentStatus == null ? "UNKNOWN" : currentStatus,
                "statusAction", action == null ? "UNKNOWN" : action,
                "requestedStatus", requestedStatus == null ? "" : requestedStatus
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareRecruiterDecisionTransition(UUID applicationId, String decision) {
        ApplicationEntity application = getApplication(applicationId);
        String action = switch (decision == null ? "" : decision) {
            case "INVITE" -> "INVITE_APPLICATION";
            case "REJECT" -> "REJECT_APPLICATION";
            case "VACANCY_CLOSED" -> "CLOSE_BY_TIMEOUT";
            default -> "UNKNOWN";
        };
        return prepareStatusTransition(application.getStatus().name(), action, "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCandidateResponseTransition(UUID applicationId, String responseType) {
        ApplicationEntity application = getApplication(applicationId);
        return prepareStatusTransition(application.getStatus().name(), "RESPOND_INVITATION", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCloseVacancyTransition(UUID vacancyId) {
        VacancyEntity vacancy = getVacancy(vacancyId);
        return prepareStatusTransition(vacancy.getStatus().name(), "CLOSE_VACANCY", VacancyStatus.CLOSED.name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareVacancyStatusTransition(UUID vacancyId, String requestedStatus) {
        VacancyEntity vacancy = getVacancy(vacancyId);
        return prepareStatusTransition(vacancy.getStatus().name(), "UPDATE_VACANCY_STATUS", requestedStatus);
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private VacancyEntity getVacancy(UUID vacancyId) {
        return vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
    }
}
