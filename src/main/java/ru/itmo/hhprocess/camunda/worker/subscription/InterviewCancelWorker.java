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
@CamundaWorkerSubscriptions.InterviewCancel
public class InterviewCancelWorker extends AbstractExternalTaskWorker {

    private final InterviewProcessService interviewProcessService;

    public InterviewCancelWorker(CamundaFormValidator formValidator,
                                 InterviewProcessService interviewProcessService) {
        super(formValidator);
        this.interviewProcessService = interviewProcessService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID interviewId = variables.readRequiredUuid("interviewId");
        UUID recruiterUserId = variables.readUuid("recruiterUserId");
        String cancelReason = variables.stringValue("cancelReason");
        return switch (activityId) {
            case "ValidateRecruiterCancelInterview" -> interviewProcessService.validateRecruiterCancelInterview(
                    interviewId, recruiterUserId, variables.stringValue("starterUserId"), cancelReason);
            case "CancelInterviewByRecruiter" -> interviewProcessService.cancelInterviewByRecruiter(
                    interviewId, recruiterUserId, variables.stringValue("starterUserId"), cancelReason);
            case "ReleaseRecruiterCancelSlot" -> interviewProcessService.releaseRecruiterCancelSlot(interviewId);
            case "ReturnCancelApplicationToReview" -> interviewProcessService.returnCancelApplicationToReview(
                    interviewId, recruiterUserId, variables.stringValue("starterUserId"), cancelReason);
            case "RecordRecruiterCancelHistory" -> interviewProcessService.recordRecruiterCancelHistory(
                    interviewId, recruiterUserId, variables.stringValue("starterUserId"));
            default -> Map.of("interviewCancelIgnored", true, "activityId", activityId);
        };
    }
}
