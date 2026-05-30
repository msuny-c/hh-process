package ru.itmo.hhprocess.camunda;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.service.VacancyService;

@Service
@RequiredArgsConstructor
public class CamundaVacancyProcessService {
    private final CamundaProperties properties;
    private final ObjectProvider<CamundaRestClient> camundaRestClientProvider;
    private final VacancyService vacancyService;

    public boolean enabled() {
        return properties.isEnabled();
    }

    public VacancyResponse create(CreateVacancyRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.VACANCY_OPERATION, "CREATE");
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.VACANCY_TITLE, request.getTitle());
        variables.put(CamundaVariables.VACANCY_DESCRIPTION, request.getDescription());
        variables.put(CamundaVariables.REQUIRED_SKILLS_CSV, String.join(",", request.getRequiredSkills()));
        variables.put(CamundaVariables.SCREENING_THRESHOLD, request.getScreeningThreshold());
        camundaRestClientProvider.getObject().startProcessByKey(properties.getVacancyProcessKey(), "vacancy-create-" + UUID.randomUUID(), variables);
        return VacancyResponse.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .requiredSkills(request.getRequiredSkills())
                .screeningThreshold(request.getScreeningThreshold())
                .status("PROCESS_STARTED")
                .build();
    }

    public VacancyResponse update(UUID vacancyId, UpdateVacancyRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.VACANCY_OPERATION, "UPDATE");
        variables.put(CamundaVariables.VACANCY_ID, vacancyId.toString());
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.VACANCY_TITLE, request.getTitle());
        variables.put(CamundaVariables.VACANCY_DESCRIPTION, request.getDescription());
        if (request.getRequiredSkills() != null) {
            variables.put(CamundaVariables.REQUIRED_SKILLS_CSV, String.join(",", request.getRequiredSkills()));
        }
        variables.put(CamundaVariables.SCREENING_THRESHOLD, request.getScreeningThreshold());
        if (request.getStatus() != null) {
            variables.put(CamundaVariables.VACANCY_STATUS, request.getStatus().name());
        }
        camundaRestClientProvider.getObject().startProcessByKey(properties.getVacancyProcessKey(), "vacancy-update-" + vacancyId, variables);
        return VacancyResponse.builder().id(vacancyId).status("PROCESS_STARTED").build();
    }

    public VacancyResponse updateStatus(UUID vacancyId, UpdateVacancyStatusRequest request) {
        if (request.getStatus() == VacancyStatus.CLOSED) {
            CloseVacancyRequest closeRequest = new CloseVacancyRequest();
            closeRequest.setReason("Closed via status update");
            return close(vacancyId, closeRequest);
        }
        UpdateVacancyRequest updateRequest = new UpdateVacancyRequest();
        updateRequest.setStatus(request.getStatus());
        return update(vacancyId, updateRequest);
    }

    public VacancyResponse close(UUID vacancyId, CloseVacancyRequest request) {
        UserEntity recruiter = vacancyService.getRecruiterUserForCurrentUser();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.VACANCY_OPERATION, "CLOSE");
        variables.put(CamundaVariables.VACANCY_ID, vacancyId.toString());
        variables.put(CamundaVariables.RECRUITER_USER_ID, recruiter.getEmail());
        variables.put(CamundaVariables.VACANCY_CLOSE_REASON, request.getReason());
        camundaRestClientProvider.getObject().startProcessByKey(properties.getVacancyProcessKey(), "vacancy-close-" + vacancyId, variables);
        return VacancyResponse.builder().id(vacancyId).status("PROCESS_STARTED").build();
    }
}
