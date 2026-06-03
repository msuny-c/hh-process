package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.entity.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationResponseService {

    private final ApplicationRepository applicationRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final AuthService authService;
    private final ru.itmo.hhprocess.camunda.CamundaWorkflowFacade camundaWorkflowFacade;

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

        if (!camundaWorkflowFacade.invitationResponded(application, candidateUser, request.getResponseType(), request.getMessage())) {
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
        for (int attempt = 0; attempt < 24; attempt++) {
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
}
