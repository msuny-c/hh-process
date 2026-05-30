package ru.itmo.hhprocess.camunda;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.dto.recruiter.CancelInterviewRequest;
import ru.itmo.hhprocess.dto.recruiter.InterviewActionResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.service.VacancyService;

@Service
@RequiredArgsConstructor
public class CamundaInterviewProcessService {
    private final CamundaProperties properties;
    private final ObjectProvider<CamundaRestClient> camundaRestClientProvider;
    private final VacancyService vacancyService;

    public boolean enabled() {
        return properties.isEnabled();
    }

    public InterviewActionResponse cancel(UUID interviewId, CancelInterviewRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.INTERVIEW_ID, interviewId.toString());
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.INTERVIEW_CANCEL_REASON, request.getReason());
        camundaRestClientProvider.getObject().startProcessByKey(properties.getCancelInterviewProcessKey(), "interview-cancel-" + interviewId, variables);
        return InterviewActionResponse.builder()
                .interviewId(interviewId)
                .status("PROCESS_STARTED")
                .message("Cancel interview process started")
                .build();
    }
}
