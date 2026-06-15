package ru.itmo.hhprocess.camunda.worker.subscription;

import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.InvitationResponseService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public abstract class ApplicationFlowWorker extends AbstractExternalTaskWorker {

    private final ApplicationService applicationService;
    private final InterviewProcessService interviewProcessService;
    private final InvitationResponseService invitationResponseService;
    private final NotificationService notificationService;
    private final VacancyLifecycleService vacancyLifecycleService;

    protected ApplicationFlowWorker(CamundaFormValidator formValidator,
                                    ApplicationService applicationService,
                                    InterviewProcessService interviewProcessService,
                                    InvitationResponseService invitationResponseService,
                                    NotificationService notificationService,
                                    VacancyLifecycleService vacancyLifecycleService) {
        super(formValidator);
        this.applicationService = applicationService;
        this.interviewProcessService = interviewProcessService;
        this.invitationResponseService = invitationResponseService;
        this.notificationService = notificationService;
        this.vacancyLifecycleService = vacancyLifecycleService;
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
            case "NotifyScreeningFailed" -> notificationService.notifyScreeningFailed(applicationId);
            case "NotifyRecruiter" -> notificationService.notifyRecruiter(applicationId);
            case "PersistRejection" -> {
                var rejection = applicationService.rejectApplication(
                        applicationId, variables.stringValue("recruiterComment"));
                Map<String, Object> result = new LinkedHashMap<>(
                        CamundaProcessVariables.rejectionPersisted(
                                rejection.application(), rejection.idempotent(), rejection.terminal()));
                var application = applicationService.notifyApplicationRejected(applicationId);
                result.putAll(CamundaProcessVariables.notificationSent(application));
                yield result;
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
            case "PersistRejectionToDb" -> {
                var rejection = applicationService.rejectApplication(
                        applicationId, variables.stringValue("recruiterComment"));
                yield CamundaProcessVariables.rejectionPersisted(
                        rejection.application(), rejection.idempotent(), rejection.terminal());
            }
            case "NotifyRejection" -> {
                var application = applicationService.notifyApplicationRejected(applicationId);
                yield CamundaProcessVariables.notificationSent(application);
            }
            case "PersistInvitation" -> {
                String invitationMessage = variables.stringValue("invitationMessage");
                Map<String, Object> result = new LinkedHashMap<>(interviewProcessService.persistInvitation(
                        applicationId,
                        invitationMessage,
                        variables.scheduledAtOrDefault(variables.readValue("scheduledAt")),
                        variables.durationOrDefault(variables.readValue("durationMinutes"))
                ));
                result.putAll(interviewProcessService.notifyInvitation(applicationId, invitationMessage));
                yield result;
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
            case "NotifyInvitation" -> interviewProcessService.notifyInvitation(
                    applicationId, variables.stringValue("invitationMessage"));
            case "PersistCandidateResponse" -> {
                Map<String, Object> result = new LinkedHashMap<>(invitationResponseService.persistCandidateResponse(
                        applicationId,
                        ResponseType.valueOf(variables.stringValue("responseType")),
                        variables.stringValue("responseMessage")
                ));
                result.putAll(invitationResponseService.notifyCandidateResponse(applicationId));
                yield result;
            }
            case "CheckInvitationStillActive" -> invitationResponseService.checkInvitationStillActive(applicationId);
            case "SaveCandidateResponse" -> invitationResponseService.saveCandidateResponse(
                    applicationId,
                    variables.requiredResponseType(variables.stringValue("responseType")),
                    variables.stringValue("responseMessage")
            );
            case "MarkCandidateResponseReceived" -> invitationResponseService.markCandidateResponseReceived(applicationId);
            case "RecordCandidateResponseHistory" -> invitationResponseService.recordCandidateResponseHistory(applicationId);
            case "PersistCandidateResponseToDb" -> invitationResponseService.persistCandidateResponse(
                    applicationId,
                    ResponseType.valueOf(variables.stringValue("responseType")),
                    variables.stringValue("responseMessage")
            );
            case "NotifyCandidateResponse" -> invitationResponseService.notifyCandidateResponse(applicationId);
            case "HandleVacancyClosedMessage" -> vacancyLifecycleService.handleVacancyClosedMessage(
                    applicationId, variables.stringValue("closeReason"));
            default -> Map.of("adapterCompleted", true, "activityId", activityId);
        };
    }
}
