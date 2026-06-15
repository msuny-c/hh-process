package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.TimeoutBatchProcessor;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.Timeout
public class TimeoutWorker extends AbstractExternalTaskWorker {
    private final ApplicationService applicationService;
    private final TimeoutBatchProcessor timeoutBatchProcessor;

    public TimeoutWorker(CamundaFormValidator formValidator,
                         ApplicationService applicationService, TimeoutBatchProcessor timeoutBatchProcessor) {
        super(formValidator);
        this.applicationService = applicationService;
        this.timeoutBatchProcessor = timeoutBatchProcessor;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        if ("CloseByTimeout".equals(activityId)) {
            var result = applicationService.closeByTimeout(variables.readRequiredUuid("applicationId"));
            return CamundaProcessVariables.timeoutClosed(result.application(), result.closed(), result.idempotent());
        }
        return switch (activityId) {
            case "FindOneExpiredInvitation" -> timeoutBatchProcessor.findOneExpiredInvitation();
            case "CancelExpiredInvitationInterview" -> timeoutBatchProcessor.cancelExpiredInvitationInterview(variables.readRequiredUuid("expiredApplicationId"));
            case "ReleaseExpiredInvitationSlot" -> timeoutBatchProcessor.releaseExpiredInvitationSlot(variables.readRequiredUuid("expiredApplicationId"));
            case "CloseExpiredInvitationApplication" -> timeoutBatchProcessor.closeExpiredInvitationApplication(variables.readRequiredUuid("expiredApplicationId"));
            case "RecordExpiredInvitationHistory" -> timeoutBatchProcessor.recordExpiredInvitationHistory(variables.readRequiredUuid("expiredApplicationId"));
            case "NotifyExpiredInvitationParticipants" -> timeoutBatchProcessor.notifyExpiredInvitationParticipants(variables.readRequiredUuid("expiredApplicationId"));
            case "CompleteExpiredInvitationProcess" -> timeoutBatchProcessor.completeExpiredInvitationProcess(variables.readRequiredUuid("expiredApplicationId"));
            case "ProcessOneExpiredInvitation" -> {
                int batchClosed = timeoutBatchProcessor.processOneExpired();
                yield Map.of("batchClosed", batchClosed, "expiredFound", batchClosed > 0, "timeoutBatchIterationCompleted", true);
            }
            default -> Map.of("timeoutTaskIgnored", true, "activityId", activityId);
        };
    }
}
