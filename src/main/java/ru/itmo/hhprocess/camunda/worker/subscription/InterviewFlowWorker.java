package ru.itmo.hhprocess.camunda.worker.subscription;

import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.InterviewProcessService;

import java.util.Map;
import java.util.UUID;

public abstract class InterviewFlowWorker extends AbstractExternalTaskWorker {

    private final InterviewProcessService interviewProcessService;

    protected InterviewFlowWorker(CamundaFormValidator formValidator, InterviewProcessService interviewProcessService) {
        super(formValidator);
        this.interviewProcessService = interviewProcessService;
    }

    protected Map<String, Object> handleAdminReset(String activityId, CamundaTaskVariables variables) {
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
            case "ResetInterviewToDb" -> interviewProcessService.resetInterviewByAdminInDb(
                    interviewId, adminUserId, resetReason);
            case "NotifyAdminResetParticipants" -> interviewProcessService.notifyAdminInterviewReset(
                    variables.readRequiredUuid("applicationId"), resetReason);
            default -> interviewProcessService.resetInterviewByAdmin(interviewId, adminUserId, resetReason);
        };
    }

    protected Map<String, Object> handleCancel(String activityId, CamundaTaskVariables variables) {
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
            case "NotifyRecruiterCancelParticipants" -> interviewProcessService.notifyRecruiterCancelParticipants(
                    variables.readRequiredUuid("applicationId"), cancelReason);
            default -> Map.of("interviewCancelIgnored", true, "activityId", activityId);
        };
    }
}
