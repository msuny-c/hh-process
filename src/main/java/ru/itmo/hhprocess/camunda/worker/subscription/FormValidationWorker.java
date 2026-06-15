package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.InterviewProcessService;
import ru.itmo.hhprocess.service.InvitationResponseService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.FormValidation
public class FormValidationWorker extends AbstractExternalTaskWorker {
    private final ApplicationService applicationService;
    private final InterviewProcessService interviewProcessService;
    private final InvitationResponseService invitationResponseService;

    public FormValidationWorker(CamundaFormValidator formValidator,
                                ApplicationService applicationService, InterviewProcessService interviewProcessService,
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
            case "ValidateApplyToVacancyForm" -> CamundaProcessVariables.applyToVacancyValidated(
                    applicationService.validateApplyToVacancyForm(
                            applicationId,
                            variables.readUuid("vacancyId"),
                            variables.readUuid("candidateUserId"),
                            variables.stringValue("starterUserId"),
                            variables.stringValue("resumeText"),
                            variables.stringValue("coverLetter")));
            case "ValidateRecruiterDecisionForm" -> {
                applicationService.validateRecruiterDecisionForm(
                        variables.required(applicationId, "applicationId"),
                        variables.stringValue("decision"),
                        variables.stringValue("recruiterComment"));
                yield CamundaProcessVariables.formValidated();
            }
            case "ValidateInvitationForm" -> interviewProcessService.validateInvitationForm(
                    variables.required(applicationId, "applicationId"),
                    variables.stringValue("invitationMessage"),
                    variables.requiredScheduledAt(variables.readValue("scheduledAt")),
                    variables.requiredDurationMinutes(variables.readValue("durationMinutes")));
            case "ValidateCandidateResponseForm" -> invitationResponseService.validateCandidateResponseForm(
                    variables.required(applicationId, "applicationId"),
                    variables.stringValue("responseType"),
                    variables.stringValue("responseMessage"));
            default -> Map.of("formValidationIgnored", true, "activityId", activityId);
        };
    }
}
