package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseRequest;
import ru.itmo.hhprocess.dto.candidate.InvitationResponseResponse;
import ru.itmo.hhprocess.entity.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.messaging.producer.NotificationRequestPublisher;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationResponseService {

    private final ApplicationRepository applicationRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final HistoryService historyService;
    private final NotificationRequestPublisher notificationRequestPublisher;
    private final AuthService authService;

    @Transactional
    public InvitationResponseResponse respond(UUID applicationId, InvitationResponseRequest request) {
        UserEntity candidateUser = authService.getCurrentUser();
        boolean candidate = candidateUser.getRoles().stream().anyMatch(r -> "CANDIDATE".equals(r.getCode()));
        if (!candidate) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Candidate access required");
        }

        ApplicationEntity application = applicationRepository.findById(applicationId)
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

        invitationResponseRepository.save(InvitationResponseEntity.builder()
                .application(application)
                .candidateUser(candidateUser)
                .responseType(request.getResponseType())
                .message(request.getMessage())
                .build());

        application.setStatus(ApplicationStatus.INVITATION_RESPONDED);
        application.setResponseReceivedAt(now);
        applicationRepository.save(application);

        historyService.record(application,
                ApplicationStatus.INVITED,
                ApplicationStatus.INVITATION_RESPONDED,
                candidateUser);

        notificationRequestPublisher.publishAfterCommit(application.getVacancy().getRecruiterUser(), application,
                ru.itmo.hhprocess.enums.NotificationType.INVITATION_RESPONSE,
                "Candidate responded to interview invitation");

        return InvitationResponseResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.INVITATION_RESPONDED.toExternalStatus())
                .message("Response sent")
                .build();
    }
}
