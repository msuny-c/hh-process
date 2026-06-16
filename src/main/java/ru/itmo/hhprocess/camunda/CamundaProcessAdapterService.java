package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.admin.AdminCreateUserRequest;
import ru.itmo.hhprocess.dto.admin.AdminUserProvisionResponse;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.service.AdminUserProvisioningService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaProcessAdapterService {

    private final CamundaFormValidator formValidator;
    private final AdminUserProvisioningService adminUserProvisioningService;
    private final CamundaPermissionAdapter permissionAdapter;
    private final CamundaFormValueParser formValueParser;
    private final CamundaUserResolver userResolver;
    private final CamundaUiQueryAdapter uiQueryAdapter;
    private final CamundaNotificationAdapter notificationAdapter;
    private final CamundaVacancyAdapter vacancyAdapter;
    private final CamundaInterviewAdapter interviewAdapter;
    private final CamundaApplicationAdapter applicationAdapter;

    @Transactional
    public Map<String, Object> autoScreen(UUID applicationId) {
        return applicationAdapter.autoScreen(applicationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareAutoScreen(UUID applicationId) {
        return applicationAdapter.prepareAutoScreen(applicationId);
    }

    @Transactional
    public Map<String, Object> saveAutoScreenDecision(UUID applicationId, boolean screeningPassed, int screeningScore) {
        return applicationAdapter.saveAutoScreenDecision(applicationId, screeningPassed, screeningScore);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveOperationPermission(String role, String operation, boolean ownership) {
        return permissionAdapter.resolveOperationPermission(role, operation, ownership);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCreateVacancyPermission(String starterUserId, UUID recruiterUserId) {
        return permissionAdapter.resolveCreateVacancyPermission(starterUserId, recruiterUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveRecruiterDecisionPermission(String starterUserId, UUID applicationId) {
        return permissionAdapter.resolveRecruiterDecisionPermission(starterUserId, applicationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCandidateResponsePermission(String starterUserId, UUID applicationId) {
        return permissionAdapter.resolveCandidateResponsePermission(starterUserId, applicationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveAdminResetPermission(String starterUserId, UUID adminUserId) {
        return permissionAdapter.resolveAdminResetPermission(starterUserId, adminUserId);
    }

    @Transactional
    public Map<String, Object> provisionUserFromAdminForm(String starterUserId, String role, String email,
                                                          String password, String firstName, String lastName) {
        userResolver.resolveUserFromCamundaStarter(starterUserId, "ADMIN");
        String normalizedRole = formValidator.requiredChoice(role, "Role", Set.of("CANDIDATE", "RECRUITER"));
        AdminCreateUserRequest request = new AdminCreateUserRequest();
        request.setEmail(formValueParser.normalizeProvisionEmail(email));
        request.setPassword(formValueParser.normalizeProvisionPassword(password));
        request.setFirstName(formValueParser.normalizeRequiredText(firstName, "First name", 255));
        request.setLastName(formValueParser.normalizeRequiredText(lastName, "Last name", 255));

        try {
            AdminUserProvisionResponse response = "CANDIDATE".equals(normalizedRole)
                    ? adminUserProvisioningService.createCandidate(request)
                    : adminUserProvisioningService.createRecruiter(request);
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("provisionedUserId", response.getUserId());
            variables.put("provisionedEmail", response.getEmail());
            variables.put("provisionedRole", response.getRole());
            variables.put("provisionedCamundaUserId", response.getCamundaUserId());
            variables.put("provisioningResultMessage", "User created in application DB and Camunda");
            return variables;
        } catch (ApiException e) {
            if (e.getHttpStatus() == HttpStatus.CONFLICT) {
                throw new CamundaFormValidationException("Email", e.getMessage());
            }
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> dispatchNotification(String notificationKind, UUID applicationId, UUID vacancyId,
                                                    String invitationMessage, String closeReason, String cancelReason,
                                                    String resetReason, String notificationTemplateCode) {
        return notificationAdapter.dispatchNotification(notificationKind, applicationId, vacancyId,
                invitationMessage, closeReason, cancelReason, resetReason, notificationTemplateCode);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareNotificationDecision(String notificationKind, UUID applicationId, UUID vacancyId,
                                                           String recipientRole) {
        return notificationAdapter.prepareNotificationDecision(notificationKind, applicationId, vacancyId, recipientRole);
    }

    @Transactional
    public Map<String, Object> notifyScreeningFailed(UUID applicationId) {
        return notificationAdapter.notifyScreeningFailed(applicationId);
    }

    @Transactional
    public Map<String, Object> notifyRecruiter(UUID applicationId) {
        return notificationAdapter.notifyRecruiter(applicationId);
    }

    @Transactional
    public Map<String, Object> rejectApplication(UUID applicationId, String comment) {
        return applicationAdapter.rejectApplication(applicationId, comment);
    }

    @Transactional
    public Map<String, Object> notifyApplicationRejected(UUID applicationId) {
        return notificationAdapter.notifyApplicationRejected(applicationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateInvitationForm(UUID applicationId, String message, Instant scheduledAt,
                                                       int durationMinutes) {
        return applicationAdapter.validateInvitationForm(applicationId, message, scheduledAt, durationMinutes);
    }

    @Transactional
    public Map<String, Object> persistInvitation(UUID applicationId, String message, Instant scheduledAt,
                                                 int durationMinutes) {
        return applicationAdapter.persistInvitation(applicationId, message, scheduledAt, durationMinutes);
    }

    @Transactional
    public Map<String, Object> saveInvitationToDb(UUID applicationId, String message) {
        return applicationAdapter.saveInvitationToDb(applicationId, message);
    }

    @Transactional
    public Map<String, Object> createInvitationInterview(UUID applicationId, String message, Instant scheduledAt,
                                                         int durationMinutes) {
        return applicationAdapter.createInvitationInterview(applicationId, message, scheduledAt, durationMinutes);
    }

    @Transactional
    public Map<String, Object> reserveInvitationSlot(UUID applicationId, UUID interviewId, Instant scheduledAt,
                                                     int durationMinutes) {
        return applicationAdapter.reserveInvitationSlot(applicationId, interviewId, scheduledAt, durationMinutes);
    }

    @Transactional
    public Map<String, Object> recordInvitationHistory(UUID applicationId) {
        return applicationAdapter.recordInvitationHistory(applicationId);
    }

    @Transactional
    public Map<String, Object> notifyInvitation(UUID applicationId, String message) {
        return notificationAdapter.notifyInvitation(applicationId, message);
    }

    @Transactional
    public Map<String, Object> persistCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        return applicationAdapter.persistCandidateResponse(applicationId, responseType, message);
    }

    @Transactional
    public Map<String, Object> notifyCandidateResponse(UUID applicationId) {
        return notificationAdapter.notifyCandidateResponse(applicationId);
    }

    @Transactional
    public Map<String, Object> closeByTimeout(UUID applicationId) {
        return applicationAdapter.closeByTimeout(applicationId);
    }

    @Transactional
    public Map<String, Object> closeVacancyApplications(UUID vacancyId, String reason) {
        return vacancyAdapter.closeVacancyApplications(vacancyId, reason);
    }

    @Transactional
    public Map<String, Object> closeVacancyApplicationsInDb(UUID vacancyId, String reason) {
        return vacancyAdapter.closeVacancyApplicationsInDb(vacancyId, reason);
    }


    @Transactional
    public Map<String, Object> correlateVacancyClosedApplications(UUID vacancyId, String reason) {
        return vacancyAdapter.correlateVacancyClosedApplications(vacancyId, reason);
    }

    @Transactional
    public Map<String, Object> handleVacancyClosedMessage(UUID applicationId, String reason) {
        return vacancyAdapter.handleVacancyClosedMessage(applicationId, reason);
    }

    @Transactional
    public Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId) {
        return notificationAdapter.notifyVacancyClosedCandidates(vacancyId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareStatusTransition(String currentStatus, String action, String requestedStatus) {
        return vacancyAdapter.prepareStatusTransition(currentStatus, action, requestedStatus);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareRecruiterDecisionTransition(UUID applicationId, String decision) {
        return vacancyAdapter.prepareRecruiterDecisionTransition(applicationId, decision);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCandidateResponseTransition(UUID applicationId, String responseType) {
        return vacancyAdapter.prepareCandidateResponseTransition(applicationId, responseType);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCloseVacancyTransition(UUID vacancyId) {
        return vacancyAdapter.prepareCloseVacancyTransition(vacancyId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareVacancyStatusTransition(UUID vacancyId, String requestedStatus) {
        return vacancyAdapter.prepareVacancyStatusTransition(vacancyId, requestedStatus);
    }

    @Transactional
    public Map<String, Object> resetInterviewByAdmin(UUID interviewId, UUID adminUserId, String reason) {
        return interviewAdapter.resetInterviewByAdmin(interviewId, adminUserId, reason);
    }

    @Transactional
    public Map<String, Object> resetInterviewByAdminInDb(UUID interviewId, UUID adminUserId, String reason) {
        return interviewAdapter.resetInterviewByAdminInDb(interviewId, adminUserId, reason);
    }

    @Transactional
    public Map<String, Object> notifyAdminInterviewReset(UUID applicationId, String reason) {
        return notificationAdapter.notifyAdminInterviewReset(applicationId, reason);
    }

    @Transactional
    public Map<String, Object> rollbackApplicationTransaction(UUID applicationId, String reason) {
        return applicationAdapter.rollbackApplicationTransaction(applicationId, reason);
    }

    @Transactional
    public Map<String, Object> rollbackVacancyTransaction(UUID vacancyId, String reason) {
        return vacancyAdapter.rollbackVacancyTransaction(vacancyId, reason);
    }


    @Transactional(readOnly = true)
    public Map<String, Object> validateApplyToVacancyForm(UUID applicationId, UUID vacancyId, UUID candidateUserId,
                                                          String starterUserId, String resumeText, String coverLetter) {
        return applicationAdapter.validateApplyToVacancyForm(applicationId, vacancyId, candidateUserId,
                starterUserId, resumeText, coverLetter);
    }

    @Transactional
    public Map<String, Object> createApplicationFromCamundaForm(UUID existingApplicationId, UUID vacancyId, UUID candidateUserId,
                                                                String starterUserId, String resumeText, String coverLetter,
                                                                String processInstanceId) {
        return applicationAdapter.createApplicationFromCamundaForm(existingApplicationId, vacancyId, candidateUserId,
                starterUserId, resumeText, coverLetter, processInstanceId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterDecisionForm(UUID applicationId, String decision, String comment) {
        return applicationAdapter.validateRecruiterDecisionForm(applicationId, decision, comment);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCandidateResponseForm(UUID applicationId, String responseType, String message) {
        return applicationAdapter.validateCandidateResponseForm(applicationId, responseType, message);
    }

    public ResponseType requiredResponseType(String raw) {
        return formValueParser.requiredResponseType(raw);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRejectionAllowed(UUID applicationId, String comment) {
        return applicationAdapter.validateRejectionAllowed(applicationId, comment);
    }

    @Transactional
    public Map<String, Object> cancelRejectionInterviewIfAny(UUID applicationId, String comment) {
        return applicationAdapter.cancelRejectionInterviewIfAny(applicationId, comment);
    }

    @Transactional
    public Map<String, Object> markApplicationRejected(UUID applicationId, String comment) {
        return applicationAdapter.markApplicationRejected(applicationId, comment);
    }

    @Transactional
    public Map<String, Object> recordRejectionHistory(UUID applicationId) {
        return applicationAdapter.recordRejectionHistory(applicationId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> checkInvitationStillActive(UUID applicationId) {
        return applicationAdapter.checkInvitationStillActive(applicationId);
    }

    @Transactional
    public Map<String, Object> saveCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        return applicationAdapter.saveCandidateResponse(applicationId, responseType, message);
    }

    @Transactional
    public Map<String, Object> markCandidateResponseReceived(UUID applicationId) {
        return applicationAdapter.markCandidateResponseReceived(applicationId);
    }

    @Transactional
    public Map<String, Object> recordCandidateResponseHistory(UUID applicationId) {
        return applicationAdapter.recordCandidateResponseHistory(applicationId);
    }


    @Transactional(readOnly = true)
    public Map<String, Object> validateCreateVacancyForm(String starterUserId, UUID recruiterUserId, String title, String description,
                                                         Object requiredSkillsRaw, Object screeningThresholdRaw) {
        return vacancyAdapter.validateCreateVacancyForm(starterUserId, recruiterUserId, title, description,
                requiredSkillsRaw, screeningThresholdRaw);
    }

    @Transactional
    public Map<String, Object> createVacancyFromCamundaForm(String starterUserId, UUID recruiterUserId, String title, String description,
                                                            Object requiredSkillsRaw, Object screeningThresholdRaw,
                                                            String processInstanceId) {
        return vacancyAdapter.createVacancyFromCamundaForm(starterUserId, recruiterUserId, title, description,
                requiredSkillsRaw, screeningThresholdRaw, processInstanceId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCloseVacancyForm(UUID vacancyId, String action, String reason) {
        return vacancyAdapter.validateCloseVacancyForm(vacancyId, action, reason);
    }

    @Transactional
    public Map<String, Object> markVacancyClosed(UUID vacancyId) {
        return vacancyAdapter.markVacancyClosed(vacancyId);
    }

    @Transactional
    public Map<String, Object> cancelActiveInterviewsForVacancy(UUID vacancyId, String reason) {
        return vacancyAdapter.cancelActiveInterviewsForVacancy(vacancyId, reason);
    }

    @Transactional
    public Map<String, Object> releaseScheduleSlotsForClosedVacancy(UUID vacancyId) {
        return vacancyAdapter.releaseScheduleSlotsForClosedVacancy(vacancyId);
    }

    @Transactional
    public Map<String, Object> closeActiveApplicationsForVacancy(UUID vacancyId, String reason) {
        return vacancyAdapter.closeActiveApplicationsForVacancy(vacancyId, reason);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordVacancyClosedHistory(UUID vacancyId) {
        return vacancyAdapter.recordVacancyClosedHistory(vacancyId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateAdminResetForm(UUID interviewId, UUID adminUserId, String reason) {
        return interviewAdapter.validateAdminResetForm(interviewId, adminUserId, reason);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateInterviewCanBeReset(UUID interviewId, UUID adminUserId) {
        return interviewAdapter.validateInterviewCanBeReset(interviewId, adminUserId);
    }

    @Transactional
    public Map<String, Object> cancelInterviewByAdmin(UUID interviewId, String reason) {
        return interviewAdapter.cancelInterviewByAdmin(interviewId, reason);
    }

    @Transactional
    public Map<String, Object> releaseAdminResetSlot(UUID interviewId) {
        return interviewAdapter.releaseAdminResetSlot(interviewId);
    }

    @Transactional
    public Map<String, Object> returnApplicationToReview(UUID interviewId, UUID adminUserId, String reason) {
        return interviewAdapter.returnApplicationToReview(interviewId, adminUserId, reason);
    }

    @Transactional
    public Map<String, Object> recordAdminResetHistory(UUID interviewId, UUID adminUserId) {
        return interviewAdapter.recordAdminResetHistory(interviewId, adminUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        return vacancyAdapter.validateVacancyStatusUpdate(vacancyId, recruiterUserId, starterUserId, requestedStatus);
    }

    @Transactional
    public Map<String, Object> applyVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        return vacancyAdapter.applyVacancyStatusUpdate(vacancyId, recruiterUserId, starterUserId, requestedStatus);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterCancelInterview(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        return interviewAdapter.validateRecruiterCancelInterview(interviewId, recruiterUserId, starterUserId, reason);
    }

    @Transactional
    public Map<String, Object> cancelInterviewByRecruiter(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        return interviewAdapter.cancelInterviewByRecruiter(interviewId, recruiterUserId, starterUserId, reason);
    }

    @Transactional
    public Map<String, Object> releaseRecruiterCancelSlot(UUID interviewId) {
        return interviewAdapter.releaseRecruiterCancelSlot(interviewId);
    }

    @Transactional
    public Map<String, Object> returnCancelApplicationToReview(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        return interviewAdapter.returnCancelApplicationToReview(interviewId, recruiterUserId, starterUserId, reason);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordRecruiterCancelHistory(UUID interviewId, UUID recruiterUserId, String starterUserId) {
        return interviewAdapter.recordRecruiterCancelHistory(interviewId, recruiterUserId, starterUserId);
    }

    @Transactional
    public Map<String, Object> notifyRecruiterCancelParticipants(UUID applicationId, String reason) {
        return notificationAdapter.notifyRecruiterCancelParticipants(applicationId, reason);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateVacancyList(String starterUserId) {
        return uiQueryAdapter.loadCandidateVacancyList(starterUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationList(String starterUserId) {
        return uiQueryAdapter.loadCandidateApplicationList(starterUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationView(String starterUserId, String applicationIdText) {
        return uiQueryAdapter.loadCandidateApplicationView(starterUserId, applicationIdText);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterVacancyList(String starterUserId) {
        return uiQueryAdapter.loadRecruiterVacancyList(starterUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationList(String starterUserId) {
        return uiQueryAdapter.loadRecruiterApplicationList(starterUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationView(String starterUserId, String applicationIdText) {
        return uiQueryAdapter.loadRecruiterApplicationView(starterUserId, applicationIdText);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterSchedule(String starterUserId, Object weekOffsetRaw) {
        return uiQueryAdapter.loadRecruiterSchedule(starterUserId, weekOffsetRaw);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadNotificationList(String starterUserId) {
        return uiQueryAdapter.loadNotificationList(starterUserId);
    }

    @Transactional
    public Map<String, Object> runTimeoutReview(String starterUserId) {
        return uiQueryAdapter.runTimeoutReview(starterUserId);
    }


    public Instant requiredScheduledAt(Object value) {
        return formValueParser.requiredScheduledAt(value);
    }

    public int requiredDurationMinutes(Object value) {
        return formValueParser.requiredDurationMinutes(value);
    }

    public Instant scheduledAtOrDefault(Object value) {
        return formValueParser.scheduledAtOrDefault(value);
    }

    public int durationOrDefault(Object value) {
        return formValueParser.durationOrDefault(value);
    }


}
