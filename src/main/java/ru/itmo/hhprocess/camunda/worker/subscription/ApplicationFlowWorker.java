package ru.itmo.hhprocess.camunda.worker.subscription;

import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.InvitationResponseService;

import java.util.Map;
import java.util.UUID;

public abstract class ApplicationFlowWorker extends AbstractExternalTaskWorker {

    private final ApplicationService applicationService;
    private final InterviewProcessService interviewProcessService;
    private final InvitationResponseService invitationResponseService;

    protected ApplicationFlowWorker(CamundaFormValidator formValidator,
                                    ApplicationService applicationService,
                                    InterviewProcessService interviewProcessService,
                                    InvitationResponseService invitationResponseService) {
        super(formValidator);
        this.applicationService = applicationService;
        this.interviewProcessService = interviewProcessService;
        this.invitationResponseService = invitationResponseService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID applicationId = variables.readUuid("applicationId");
        return switch (activityId) {
            case "CreateApplicationFromForm" -> {
                var result = applicationService.createApplicationFromCamundaForm(
                        variables.readUuid("applicationId"),
                        variables.readRequiredUuid("vacancyId"),
                        variables.readUuid("candidateUserId"),
                        variables.stringValue("starterUserId"),
                        variables.stringValue("resumeText"),
                        variables.stringValue("coverLetter"),
                        variables.processInstanceId()
                );
                if (result.idempotent()) {
                    yield CamundaProcessVariables.applicationIdempotentVariables(result.application());
                }
                yield CamundaProcessVariables.applicationStartVariables(
                        result.vacancy(), result.candidate(), result.application(), result.businessKey());
            }
            case "ValidateRejectionAllowed" -> CamundaProcessVariables.rejectionAllowed(
                    applicationService.validateRejectionAllowed(
                            applicationId, variables.stringValue("recruiterComment")));
            case "CancelRejectionInterviewIfAny" -> {
                applicationService.cancelRejectionInterviewIfAny(
                        applicationId, variables.stringValue("recruiterComment"));
                yield CamundaProcessVariables.rejectionInterviewCancelled();
            }
            case "MarkApplicationRejected" -> {
                var result = applicationService.markApplicationRejected(
                        applicationId, variables.stringValue("recruiterComment"));
                yield CamundaProcessVariables.applicationRejected(
                        result.application(), result.oldStatus(), result.idempotent());
            }
            case "RecordRejectionHistory" -> {
                var application = applicationService.recordRejectionHistory(applicationId);
                yield CamundaProcessVariables.rejectionHistoryRecorded(application);
            }
            case "PersistInvitationToDb" -> interviewProcessService.saveInvitationToDb(
                    applicationId, variables.stringValue("invitationMessage"));
            case "CreateInvitationInterview" -> interviewProcessService.createInvitationInterview(
                    applicationId,
                    variables.stringValue("invitationMessage"),
                    variables.requiredScheduledAt(variables.readValue("scheduledAt")),
                    variables.requiredDurationMinutes(variables.readValue("durationMinutes"))
            );
            case "ReserveInvitationSlot" -> interviewProcessService.reserveInvitationSlot(
                    applicationId,
                    variables.readRequiredUuid("interviewId"),
                    variables.requiredScheduledAt(variables.readValue("scheduledAt")),
                    variables.requiredDurationMinutes(variables.readValue("durationMinutes"))
            );
            case "RecordInvitationHistory" -> interviewProcessService.recordInvitationHistory(applicationId);
            case "CheckInvitationStillActive" -> invitationResponseService.checkInvitationStillActive(applicationId);
            case "SaveCandidateResponse" -> invitationResponseService.saveCandidateResponse(
                    applicationId,
                    variables.requiredResponseType(variables.stringValue("responseType")),
                    variables.stringValue("responseMessage")
            );
            case "MarkCandidateResponseReceived" -> invitationResponseService.markCandidateResponseReceived(applicationId);
            case "RecordCandidateResponseHistory" -> invitationResponseService.recordCandidateResponseHistory(applicationId);
            default -> Map.of("applicationFlowIgnored", true, "activityId", activityId);
        };
    }
}
