package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionCheckService {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final CamundaIdentitySyncService camundaIdentitySyncService;

    public Map<String, Object> resolveOperationPermission(String role, String operation, boolean ownership) {
        return permissionVariables(role, operation, ownership, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCreateVacancyPermission(String starterUserId, UUID recruiterUserId) {
        UserEntity user = resolvePermissionUser(starterUserId, recruiterUserId);
        boolean ownership = user != null && user.isEnabled() && camundaIdentitySyncService.hasRole(user, "RECRUITER");
        return permissionVariables(primaryRole(user), "CREATE_VACANCY", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveRecruiterDecisionPermission(String starterUserId, UUID applicationId) {
        UserEntity user = resolvePermissionUser(starterUserId, null);
        boolean ownership = false;
        if (user != null && applicationId != null && camundaIdentitySyncService.hasRole(user, "RECRUITER")) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getVacancy().getRecruiterUser().getId().equals(user.getId()))
                    .orElse(false);
        } else if (applicationId != null) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getVacancy().getRecruiterUser() != null)
                    .orElse(false);
            return permissionVariables("RECRUITER", "REVIEW_APPLICATION", ownership, null);
        }
        return permissionVariables(primaryRole(user), "REVIEW_APPLICATION", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCandidateResponsePermission(String starterUserId, UUID applicationId) {
        UserEntity user = resolvePermissionUser(starterUserId, null);
        boolean ownership = false;
        if (user != null && applicationId != null && camundaIdentitySyncService.hasRole(user, "CANDIDATE")) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getCandidateUser().getId().equals(user.getId()))
                    .orElse(false);
        } else if (applicationId != null) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getCandidateUser() != null)
                    .orElse(false);
            return permissionVariables("CANDIDATE", "RESPOND_INVITATION", ownership, null);
        }
        return permissionVariables(primaryRole(user), "RESPOND_INVITATION", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveAdminResetPermission(String starterUserId, UUID adminUserId) {
        UserEntity user = resolvePermissionUser(starterUserId, adminUserId);
        boolean ownership = user != null && user.isEnabled() && camundaIdentitySyncService.hasRole(user, "ADMIN");
        return permissionVariables(primaryRole(user), "ADMIN_RESET", ownership, user);
    }

    private UserEntity resolvePermissionUser(String starterUserId, UUID explicitUserId) {
        try {
            if (explicitUserId != null) {
                return userRepository.findById(explicitUserId).orElse(null);
            }
            return camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> permissionVariables(String role, String operation, boolean ownership, UserEntity user) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("permissionRole", role);
        variables.put("permissionOperation", operation);
        variables.put("permissionOwnership", ownership);
        variables.put("permissionChecked", true);
        if (user != null) {
            variables.put("permissionSubjectUserId", user.getId());
        }
        return variables;
    }

    private String primaryRole(UserEntity user) {
        if (user == null) {
            return "UNKNOWN";
        }
        if (camundaIdentitySyncService.hasRole(user, "ADMIN")) {
            return "ADMIN";
        }
        if (camundaIdentitySyncService.hasRole(user, "RECRUITER")) {
            return "RECRUITER";
        }
        if (camundaIdentitySyncService.hasRole(user, "CANDIDATE")) {
            return "CANDIDATE";
        }
        return "UNKNOWN";
    }
}
