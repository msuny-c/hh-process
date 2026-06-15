package ru.itmo.hhprocess.camunda.worker.subscription;

import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;
import ru.itmo.hhprocess.service.VacancyService;

import java.util.Map;
import java.util.UUID;

public abstract class VacancyFlowWorker extends AbstractExternalTaskWorker {

    private final VacancyService vacancyService;
    private final VacancyLifecycleService vacancyLifecycleService;
    private final NotificationService notificationService;

    protected VacancyFlowWorker(CamundaFormValidator formValidator,
                                VacancyService vacancyService,
                                VacancyLifecycleService vacancyLifecycleService,
                                NotificationService notificationService) {
        super(formValidator);
        this.vacancyService = vacancyService;
        this.vacancyLifecycleService = vacancyLifecycleService;
        this.notificationService = notificationService;
    }

    protected Map<String, Object> handleCreate(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "ValidateCreateVacancyForm" -> vacancyService.validateCreateVacancyForm(
                    variables.stringValue("starterUserId"),
                    variables.readUuid("recruiterUserId"),
                    variables.stringValue("title"),
                    variables.stringValue("description"),
                    variables.readValue("requiredSkills"),
                    variables.readValue("screeningThreshold")
            );
            case "CreateVacancyFromForm" -> vacancyService.createVacancyFromCamundaForm(
                    variables.stringValue("starterUserId"),
                    variables.readUuid("recruiterUserId"),
                    variables.stringValue("title"),
                    variables.stringValue("description"),
                    variables.readValue("requiredSkills"),
                    variables.readValue("screeningThreshold"),
                    variables.processInstanceId()
            );
            default -> Map.of("vacancyCreateIgnored", true, "activityId", activityId);
        };
    }

    protected Map<String, Object> handleClose(String activityId, CamundaTaskVariables variables) {
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
            case "CloseVacancyAndApplicationsToDb" -> vacancyLifecycleService.closeVacancyApplicationsInDb(vacancyId, closeReason);
            case "NotifyVacancyClosedCandidates" -> notificationService.notifyVacancyClosedCandidates(vacancyId);
            case "CorrelateVacancyClosedApplications" -> vacancyLifecycleService.correlateVacancyClosedApplications(vacancyId, closeReason);
            default -> vacancyLifecycleService.closeVacancyApplications(vacancyId, closeReason);
        };
    }

    protected Map<String, Object> handleStatusUpdate(String activityId, CamundaTaskVariables variables) {
        UUID vacancyId = variables.readRequiredUuid("vacancyId");
        UUID recruiterUserId = variables.readUuid("recruiterUserId");
        String requestedStatus = variables.stringValue("requestedStatus");
        return switch (activityId) {
            case "ValidateVacancyStatusUpdate" -> vacancyService.validateVacancyStatusUpdate(
                    vacancyId, recruiterUserId, variables.stringValue("starterUserId"), requestedStatus);
            case "ApplyVacancyStatusUpdate" -> vacancyService.applyVacancyStatusUpdate(
                    vacancyId, recruiterUserId, variables.stringValue("starterUserId"), requestedStatus);
            default -> Map.of("vacancyStatusUpdateIgnored", true, "activityId", activityId);
        };
    }
}
