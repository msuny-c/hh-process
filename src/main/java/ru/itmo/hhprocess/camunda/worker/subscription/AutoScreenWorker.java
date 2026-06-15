package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.ScreeningService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.AutoScreen
public class AutoScreenWorker extends AbstractExternalTaskWorker {
    private final ScreeningService screeningService;
    private final ApplicationService applicationService;

    public AutoScreenWorker(CamundaFormValidator formValidator,
                            ScreeningService screeningService, ApplicationService applicationService) {
        super(formValidator);
        this.screeningService = screeningService;
        this.applicationService = applicationService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID applicationId = variables.readRequiredUuid("applicationId");
        return switch (activityId) {
            case "AutoScreenApplication" -> {
                ApplicationEntity application = applicationService.findById(applicationId);
                yield CamundaProcessVariables.prepareAutoScreenVariables(screeningService.prepareScreeningInput(application));
            }
            case "SaveAutoScreenDecision" -> {
                var result = screeningService.applyScreeningDecisionFromProcess(
                        applicationId,
                        variables.booleanValue("screeningPassed"),
                        variables.integerValue("screeningScore"));
                yield CamundaProcessVariables.saveAutoScreenDecisionVariables(
                        result.application(),
                        result.screeningResult().isPassed(),
                        variables.integerValue("screeningScore"));
            }
            default -> {
                ApplicationEntity application = applicationService.findById(applicationId);
                yield CamundaProcessVariables.prepareAutoScreenVariables(screeningService.prepareScreeningInput(application));
            }
        };
    }
}
