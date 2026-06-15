package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.VacancyClose
public class VacancyCloseWorker extends AbstractExternalTaskWorker {

    private final VacancyLifecycleService vacancyLifecycleService;
    private final NotificationService notificationService;

    public VacancyCloseWorker(CamundaFormValidator formValidator,
                              VacancyLifecycleService vacancyLifecycleService,
                              NotificationService notificationService) {
        super(formValidator);
        this.vacancyLifecycleService = vacancyLifecycleService;
        this.notificationService = notificationService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID vacancyId = variables.readRequiredUuid("vacancyId");
        String closeReason = variables.stringValue("closeReason");
        return switch (activityId) {
            case "ValidateCloseVacancyForm" -> vacancyLifecycleService.validateCloseVacancyForm(
                    vacancyId, variables.stringValue("action"), closeReason);
            case "MarkVacancyClosed" -> vacancyLifecycleService.markVacancyClosed(vacancyId);
            case "CancelActiveInterviewsForVacancy" -> vacancyLifecycleService.cancelActiveInterviewsForVacancy(vacancyId, closeReason);
            case "ReleaseScheduleSlotsForClosedVacancy" -> vacancyLifecycleService.releaseScheduleSlotsForClosedVacancy(vacancyId);
            case "CloseActiveApplicationsForVacancy" -> vacancyLifecycleService.closeActiveApplicationsForVacancy(vacancyId, closeReason);
            case "RecordVacancyClosedHistory" -> vacancyLifecycleService.recordVacancyClosedHistory(vacancyId);
            case "NotifyVacancyClosedCandidates" -> notificationService.notifyVacancyClosedCandidates(vacancyId);
            case "CorrelateVacancyClosedApplications" -> vacancyLifecycleService.correlateVacancyClosedApplications(vacancyId, closeReason);
            default -> Map.of("vacancyCloseIgnored", true, "activityId", activityId);
        };
    }
}
