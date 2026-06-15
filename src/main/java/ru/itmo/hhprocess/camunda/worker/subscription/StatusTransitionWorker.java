package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.StatusTransitionService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.StatusTransition
public class StatusTransitionWorker extends AbstractExternalTaskWorker {

    private final StatusTransitionService statusTransitionService;

    public StatusTransitionWorker(CamundaFormValidator formValidator,
                                  StatusTransitionService statusTransitionService) {
        super(formValidator);
        this.statusTransitionService = statusTransitionService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "PrepareRecruiterDecisionTransition" ->
                    statusTransitionService.prepareRecruiterDecisionTransition(
                            variables.readRequiredUuid("applicationId"),
                            variables.stringValue("decision"));
            case "PrepareCandidateResponseTransition" ->
                    statusTransitionService.prepareCandidateResponseTransition(
                            variables.readRequiredUuid("applicationId"),
                            variables.stringValue("responseType"));
            case "PrepareCloseVacancyTransition" ->
                    statusTransitionService.prepareCloseVacancyTransition(
                            variables.readRequiredUuid("vacancyId"));
            case "PrepareVacancyStatusTransition" ->
                    statusTransitionService.prepareVacancyStatusTransition(
                            variables.readRequiredUuid("vacancyId"),
                            variables.stringValue("requestedStatus"));
            default -> statusTransitionService.prepareStatusTransition("UNKNOWN", "UNKNOWN", "");
        };
    }
}
