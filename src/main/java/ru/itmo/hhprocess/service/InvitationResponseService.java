package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InvitationResponseEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationResponseService {

    private final ApplicationRepository applicationRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final AuthService authService;
    private final CamundaFormValidator formValidator;
    private final CamundaRestClient camundaRestClient;

    public InvitationResponseResponse respond(UUID applicationId, InvitationResponseRequest request) {
        UserEntity candidateUser = authService.getCurrentUser();

        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));

        if (!application.getCandidateUser().getId().equals(candidateUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Not your application");
        }

        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application is not in INVITED status");
        }

        Instant now = Instant.now();
        if (application.getInvitationExpiresAt() != null
                && now.isAfter(application.getInvitationExpiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVITATION_EXPIRED,
                    "Invitation has expired");
        }

        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Invitation response already exists");
        }

        if (!camundaRestClient.completeFirstTask("application:" + applicationId, "CandidateInvitationResponseTask",
                Map.of("responseType", request.getResponseType().name(),
                        "responseMessage", request.getMessage() == null ? "" : request.getMessage(),
                        "responseReceivedAt", Instant.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda candidate response task is not active");
        }

        application = waitForApplicationStatus(applicationId, ApplicationStatus.INVITATION_RESPONDED);
        return InvitationResponseResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.INVITATION_RESPONDED.toExternalStatus())
                .message("Response sent")
                .build();
    }

    private ApplicationEntity waitForApplicationStatus(UUID applicationId, ApplicationStatus expected) {
        for (int attempt = 0; attempt < 60; attempt++) {
            ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
            if (application.getStatus() == expected) {
                return application;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                "Camunda process did not reach " + expected + " in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Interrupted while waiting for Camunda process");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCandidateResponseForm(UUID applicationId, String responseType, String message) {
        requiredResponseType(responseType);
        formValidator.maxLength(message, "Candidate response message", 5_000);
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new CamundaFormValidationException(
                    "Application is not waiting for candidate response: " + application.getStatus());
        }
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional
    public Map<String, Object> persistCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        ApplicationEntity application = getApplication(applicationId);
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("responsePersisted", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            return Map.of("responsePersisted", false, "status", application.getStatus().name());
        }

        invitationResponseRepository.save(InvitationResponseEntity.builder()
                .application(application)
                .candidateUser(application.getCandidateUser())
                .responseType(responseType)
                .message(message)
                .build());

        application.setStatus(ApplicationStatus.INVITATION_RESPONDED);
        application.setResponseReceivedAt(Instant.now());
        applicationRepository.save(application);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.INVITATION_RESPONDED,
                application.getCandidateUser());

        return Map.of("responsePersisted", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyCandidateResponse(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INVITATION_RESPONSE, "Candidate responded to interview invitation");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> checkInvitationStillActive(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application is not waiting for candidate response: " + application.getStatus());
        }
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("invitationActive", true, "idempotent", true, "status", application.getStatus().name());
        }
        return Map.of("invitationActive", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> saveCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        ApplicationEntity application = getApplication(applicationId);
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("candidateResponseSaved", true, "idempotent", true, "status", application.getStatus().name());
        }
        invitationResponseRepository.save(InvitationResponseEntity.builder()
                .application(application)
                .candidateUser(application.getCandidateUser())
                .responseType(responseType)
                .message(message)
                .build());
        return Map.of("candidateResponseSaved", true, "responseType", responseType.name());
    }

    @Transactional
    public Map<String, Object> markCandidateResponseReceived(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus == ApplicationStatus.INVITATION_RESPONDED) {
            return Map.of("candidateResponseMarked", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (oldStatus != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Candidate response can be marked only for invited application: " + oldStatus);
        }
        application.setStatus(ApplicationStatus.INVITATION_RESPONDED);
        application.setResponseReceivedAt(Instant.now());
        applicationRepository.save(application);
        return Map.of("candidateResponseMarked", true, "oldApplicationStatus", oldStatus.name(),
                "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> recordCandidateResponseHistory(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.INVITATION_RESPONDED,
                application.getCandidateUser());
        return Map.of("candidateResponseHistoryRecorded", true, "status", application.getStatus().name());
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private ResponseType requiredResponseType(String raw) {
        return formValidator.requiredEnum(raw, "Candidate response type",
                ResponseType.class, Set.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER));
    }
}
