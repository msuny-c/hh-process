package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaPermissionAdapter {

    private final ApplicationRepository applicationRepository;
    private final CamundaUserResolver userResolver;

    @Transactional(readOnly = true)
    public Map<String, Object> resolveOperationPermission(String role, String operation, boolean ownership) {
        return Map.of(
                "permissionRole", role,
                "permissionOperation", operation,
                "permissionOwnership", ownership,
                "permissionChecked", true
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCreateVacancyPermission(String starterUserId, UUID recruiterUserId) {
        UserEntity user = userResolver.resolvePermissionUser(starterUserId, recruiterUserId);
        boolean ownership = user != null && user.isEnabled() && userResolver.hasRole(user, "RECRUITER");
        return permissionVariables(userResolver.primaryRole(user), "CREATE_VACANCY", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveRecruiterDecisionPermission(String starterUserId, UUID applicationId) {
        UserEntity user = userResolver.resolvePermissionUser(starterUserId, null);
        boolean ownership = false;
        if (user != null && applicationId != null && userResolver.hasRole(user, "RECRUITER")) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getVacancy().getRecruiterUser().getId().equals(user.getId()))
                    .orElse(false);
        } else if (applicationId != null) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getVacancy().getRecruiterUser() != null)
                    .orElse(false);
            return permissionVariables("RECRUITER", "REVIEW_APPLICATION", ownership, null);
        }
        return permissionVariables(userResolver.primaryRole(user), "REVIEW_APPLICATION", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveCandidateResponsePermission(String starterUserId, UUID applicationId) {
        UserEntity user = userResolver.resolvePermissionUser(starterUserId, null);
        boolean ownership = false;
        if (user != null && applicationId != null && userResolver.hasRole(user, "CANDIDATE")) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getCandidateUser().getId().equals(user.getId()))
                    .orElse(false);
        } else if (applicationId != null) {
            ownership = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getCandidateUser() != null)
                    .orElse(false);
            return permissionVariables("CANDIDATE", "RESPOND_INVITATION", ownership, null);
        }
        return permissionVariables(userResolver.primaryRole(user), "RESPOND_INVITATION", ownership, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveAdminResetPermission(String starterUserId, UUID adminUserId) {
        UserEntity user = userResolver.resolvePermissionUser(starterUserId, adminUserId);
        boolean ownership = user != null && user.isEnabled() && userResolver.hasRole(user, "ADMIN");
        return permissionVariables(userResolver.primaryRole(user), "ADMIN_RESET", ownership, user);
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
}
