package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyStatusRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final VacancyMapper vacancyMapper;
    private final VacancyHistoryService vacancyHistoryService;
    private final CamundaFormValidator formValidator;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties camundaProperties;

    @Transactional
    public VacancyResponse create(CreateVacancyRequest request) {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        Map<String, Object> variables = Map.of(
                "recruiterUserId", recruiterUser.getId(),
                "title", request.getTitle(),
                "description", request.getDescription() == null ? "" : request.getDescription(),
                "requiredSkills", request.getRequiredSkills() == null ? "" : String.join(", ", request.getRequiredSkills()),
                "screeningThreshold", request.getScreeningThreshold(),
                "restAutoSubmit", true,
                "startedAt", Instant.now()
        );
        String processInstanceId = camundaRestClient.startProcessByKey(
                        camundaProperties.getVacancyProcessKey(),
                        "vacancy-request:" + UUID.randomUUID(),
                        variables)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                        "Camunda vacancy create process was not started"));
        VacancyEntity vacancy = waitForVacancyCreated(processInstanceId);
        return vacancyMapper.toResponse(vacancy);
    }

    @Transactional(readOnly = true)
    public List<VacancyResponse> getMyVacancies() {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        return vacancyRepository.findByRecruiterUserId(recruiterUser.getId()).stream()
                .map(vacancyMapper::toResponse)
                .toList();
    }

    @Transactional
    public VacancyResponse updateStatus(UUID vacancyId, UpdateVacancyStatusRequest request) {
        UserEntity recruiterUser = getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = findByIdForUpdate(vacancyId);
        ensureOwnership(vacancy, recruiterUser);
        camundaRestClient.startProcessByKey(
                        camundaProperties.getVacancyStatusUpdateProcessKey(),
                        "vacancy-status:" + vacancyId + ":" + UUID.randomUUID(),
                        Map.of(
                                "vacancyId", vacancyId,
                                "recruiterUserId", recruiterUser.getId(),
                                "requestedStatus", request.getStatus().name(),
                                "requestedAt", Instant.now()
                        ))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                        "Camunda vacancy status update process was not started"));
        vacancy = waitForVacancyStatus(vacancyId, request.getStatus());
        return vacancyMapper.toResponse(vacancy);
    }

    public void ensureOwnership(VacancyEntity vacancy, UserEntity recruiterUser) {
        if (!vacancy.getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "You can only manage your own vacancies");
        }
    }

    @Transactional(readOnly = true)
    public VacancyEntity findById(UUID id) {
        return vacancyRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.VACANCY_NOT_FOUND, "Vacancy not found"));
    }

    @Transactional
    public VacancyEntity findByIdForUpdate(UUID id) {
        return vacancyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.VACANCY_NOT_FOUND, "Vacancy not found"));
    }

    @Transactional(readOnly = true)
    public UserEntity getRecruiterUserForCurrentUser() {
        UserEntity user = authService.getCurrentUser();
        boolean recruiter = user.getRoles().stream().anyMatch(r -> "RECRUITER".equals(r.getCode()));
        if (!recruiter) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Recruiter access required");
        }
        return user;
    }

    private VacancyEntity waitForVacancyCreated(String processInstanceId) {
        for (int attempt = 0; attempt < 60; attempt++) {
            var vacancy = vacancyRepository.findByCamundaProcessInstanceId(processInstanceId);
            if (vacancy.isPresent()) {
                return vacancy.get();
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not create vacancy in time");
    }

    private VacancyEntity waitForVacancyStatus(UUID vacancyId, ru.itmo.hhprocess.enums.VacancyStatus expected) {
        for (int attempt = 0; attempt < 60; attempt++) {
            VacancyEntity vacancy = findById(vacancyId);
            if (vacancy.getStatus() == expected) {
                return vacancy;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not update vacancy status in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                    "Interrupted while waiting for Camunda process");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCreateVacancyForm(String starterUserId, UUID recruiterUserId, String title,
                                                         String description, Object requiredSkillsRaw,
                                                         Object screeningThresholdRaw) {
        UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = formValidator.requiredText(title, "Vacancy title", 255);
        String normalizedDescription = formValidator.optionalText(description, "Vacancy description", 10_000);
        List<String> skills = formValidator.requiredSkills(requiredSkillsRaw);
        int threshold = formValidator.integerRange(screeningThresholdRaw, "Screening threshold", 0, 100);
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "recruiterUserId", recruiter.getId(),
                "title", normalizedTitle,
                "description", normalizedDescription,
                "requiredSkills", skills,
                "screeningThreshold", threshold
        );
    }

    @Transactional
    public Map<String, Object> createVacancyFromCamundaForm(String starterUserId, UUID recruiterUserId, String title,
                                                            String description, Object requiredSkillsRaw,
                                                            Object screeningThresholdRaw, String processInstanceId) {
        UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = formValidator.requiredText(title, "Vacancy title", 255);
        String normalizedDescription = formValidator.optionalText(description, "Vacancy description", 10_000);
        List<String> skills = formValidator.requiredSkills(requiredSkillsRaw);
        int threshold = formValidator.integerRange(screeningThresholdRaw, "Screening threshold", 0, 100);

        VacancyEntity vacancy = vacancyRepository.save(VacancyEntity.builder()
                .recruiterUser(recruiter)
                .title(normalizedTitle)
                .description(normalizedDescription)
                .status(VacancyStatus.ACTIVE)
                .requiredSkills(skills)
                .screeningThreshold(threshold)
                .camundaProcessInstanceId(processInstanceId)
                .build());
        vacancyHistoryService.record(vacancy, null, VacancyStatus.ACTIVE, recruiter);

        String businessKey = "vacancy:" + vacancy.getId();
        camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);

        return Map.of(
                "vacancyCreated", true,
                "vacancyId", vacancy.getId(),
                "recruiterUserId", recruiter.getId(),
                "title", vacancy.getTitle(),
                "vacancyTitle", vacancy.getTitle(),
                "description", vacancy.getDescription() == null ? "" : vacancy.getDescription(),
                "requiredSkills", vacancy.getRequiredSkills(),
                "screeningThreshold", vacancy.getScreeningThreshold(),
                "status", vacancy.getStatus().name(),
                "vacancyBusinessKey", businessKey
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId,
                                                           String requestedStatus) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        if (!camundaIdentitySyncService.hasRole(recruiter, "RECRUITER")) {
            throw new CamundaFormValidationException("Only RECRUITER users can update vacancies");
        }
        if (!vacancy.getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Vacancy does not belong to current recruiter");
        }
        formValidator.requiredEnum(requestedStatus, "Vacancy status", VacancyStatus.class, null);
        return Map.of("formValidated", true, "formErrorMessage", "", "oldVacancyStatus", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> applyVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId,
                                                        String requestedStatus) {
        validateVacancyStatusUpdate(vacancyId, recruiterUserId, starterUserId, requestedStatus);
        VacancyStatus newStatus = formValidator.requiredEnum(requestedStatus, "Vacancy status", VacancyStatus.class, null);
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        VacancyStatus oldStatus = vacancy.getStatus();
        if (oldStatus != newStatus) {
            vacancy.setStatus(newStatus);
            UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
            vacancyHistoryService.record(vacancy, oldStatus, newStatus, recruiter);
        }
        return Map.of(
                "vacancyStatusUpdated", true,
                "vacancyId", vacancy.getId(),
                "oldVacancyStatus", oldStatus.name(),
                "status", vacancy.getStatus().name()
        );
    }
}
