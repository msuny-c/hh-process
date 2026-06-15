package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.InterviewProcessService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.AdminInterviewReset
public class AdminInterviewResetWorker extends AbstractExternalTaskWorker {

    private final InterviewProcessService interviewProcessService;

    public AdminInterviewResetWorker(CamundaFormValidator formValidator,
                                     InterviewProcessService interviewProcessService) {
        super(formValidator);
        this.interviewProcessService = interviewProcessService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID interviewId = variables.readRequiredUuid("interviewId");
        UUID adminUserId = variables.readRequiredUuid("adminUserId");
        String resetReason = variables.stringValue("resetReason");
        return switch (activityId) {
            case "ValidateAdminResetForm" -> interviewProcessService.validateAdminResetForm(
                    interviewId, adminUserId, resetReason);
            case "ValidateInterviewCanBeReset" -> interviewProcessService.validateInterviewCanBeReset(
                    interviewId, adminUserId);
            case "CancelInterviewByAdmin" -> interviewProcessService.cancelInterviewByAdmin(interviewId, resetReason);
            case "ReleaseAdminResetSlot" -> interviewProcessService.releaseAdminResetSlot(interviewId);
            case "ReturnApplicationToReview" -> interviewProcessService.returnApplicationToReview(
                    interviewId, adminUserId, resetReason);
            case "RecordAdminResetHistory" -> interviewProcessService.recordAdminResetHistory(interviewId, adminUserId);
            default -> Map.of("adminInterviewResetIgnored", true, "activityId", activityId);
        };
    }
}
