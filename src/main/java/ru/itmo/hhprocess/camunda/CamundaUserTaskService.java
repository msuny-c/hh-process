package ru.itmo.hhprocess.camunda;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.dto.recruiter.InviteRequest;
import ru.itmo.hhprocess.dto.recruiter.InviteResponse;
import ru.itmo.hhprocess.dto.recruiter.RejectRequest;
import ru.itmo.hhprocess.dto.recruiter.RejectResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.AuthService;
import ru.itmo.hhprocess.service.RecruiterDecisionService;
import ru.itmo.hhprocess.service.VacancyService;

@Service
@RequiredArgsConstructor
public class CamundaUserTaskService {
    private final CamundaProperties properties;
    private final ObjectProvider<CamundaRestClient> camundaRestClientProvider;
    private final AuthService authService;
    private final VacancyService vacancyService;
    private final RecruiterDecisionService recruiterDecisionService;
    private final ApplicationService applicationService;

    public boolean enabled() {
        return properties.isEnabled();
    }

    public RejectResponse reject(UUID applicationId, RejectRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        recruiterDecisionService.getApplication(applicationId);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.APPLICATION_ID, applicationId.toString());
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.RECRUITER_DECISION, "REJECT");
        variables.put(CamundaVariables.RECRUITER_MESSAGE, request.getComment());
        camundaRestClientProvider.getObject().completeUserTaskByApplicationId(
                properties.getRecruiterReviewTaskKey(),
                applicationId,
                variables
        );
        return RejectResponse.builder()
                .applicationId(applicationId)
                .status("PROCESS_TASK_COMPLETED")
                .build();
    }

    public InviteResponse invite(UUID applicationId, InviteRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        recruiterDecisionService.getApplication(applicationId);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.APPLICATION_ID, applicationId.toString());
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.RECRUITER_DECISION, "INVITE");
        variables.put(CamundaVariables.RECRUITER_MESSAGE, request.getMessage());
        variables.put(CamundaVariables.SCHEDULED_AT, request.getScheduledAt().toString());
        variables.put(CamundaVariables.DURATION_MINUTES, request.getDurationMinutes());
        camundaRestClientProvider.getObject().completeUserTaskByApplicationId(
                properties.getRecruiterReviewTaskKey(),
                applicationId,
                variables
        );
        return InviteResponse.builder()
                .applicationId(applicationId)
                .status("PROCESS_TASK_COMPLETED")
                .scheduledAt(request.getScheduledAt())
                .durationMinutes(request.getDurationMinutes())
                .build();
    }

    public InvitationResponseResponse respond(UUID applicationId, InvitationResponseRequest request) {
        UserEntity candidate = authService.getCurrentUser();
        applicationService.getApplicationForCandidate(applicationId);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.APPLICATION_ID, applicationId.toString());
        variables.put(CamundaVariables.CANDIDATE_USER_ID, candidate.getEmail());
        variables.put(CamundaVariables.CANDIDATE_RESPONSE, request.getResponseType().name());
        variables.put(CamundaVariables.CANDIDATE_MESSAGE, request.getMessage());
        camundaRestClientProvider.getObject().completeUserTaskByApplicationId(
                properties.getCandidateResponseTaskKey(),
                applicationId,
                variables
        );
        return InvitationResponseResponse.builder()
                .applicationId(applicationId)
                .status(ApplicationStatus.INVITATION_RESPONDED.toExternalStatus())
                .message("Process task completed")
                .build();
    }
}
