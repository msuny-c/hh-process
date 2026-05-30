package ru.itmo.hhprocess.camunda;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.service.AuthService;

@Service
@RequiredArgsConstructor
public class CamundaProcessService {
    private final CamundaProperties properties;
    private final ObjectProvider<CamundaRestClient> camundaRestClientProvider;
    private final AuthService authService;

    public boolean enabled() {
        return properties.isEnabled();
    }

    public CreateApplicationResponse startCandidateApplication(UUID vacancyId, CreateApplicationRequest request) {
        UserEntity candidate = authService.getCurrentUser();
        String processInstanceId = camundaRestClientProvider.getObject().startCandidateApplication(
                vacancyId,
                candidate.getEmail(),
                request.getResumeText(),
                request.getCoverLetter()
        );
        return CreateApplicationResponse.builder()
                .applicationId(null)
                .status("PROCESS_STARTED")
                .message(processInstanceId)
                .build();
    }
}
