package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.UserRepository;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CamundaUserResolver {

    private final UserRepository userRepository;

    public UserEntity resolveRecruiterFromCamundaStarter(String starterUserId) {
        return resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
    }

    public UserEntity resolveRecruiterForVacancyCommand(String starterUserId, UUID recruiterUserId) {
        if (recruiterUserId != null) {
            UserEntity recruiter = userRepository.findById(recruiterUserId)
                    .orElseThrow(() -> new CamundaFormValidationException("Recruiter user not found: " + recruiterUserId));
            if (!hasRole(recruiter, "RECRUITER")) {
                throw new CamundaFormValidationException("Only RECRUITER users can create vacancies");
            }
            if (!recruiter.isEnabled()) {
                throw new CamundaFormValidationException("User is disabled: " + recruiterUserId);
            }
            return recruiter;
        }
        return resolveRecruiterFromCamundaStarter(starterUserId);
    }

    public UserEntity resolveUserFromCamundaStarter(String starterUserId, String requiredRole) {
        if (starterUserId == null || starterUserId.isBlank()) {
            throw new CamundaFormValidationException("Camunda process starter is not available. Start the process as a synced application user.");
        }
        String normalizedStarter = starterUserId.trim().toLowerCase(java.util.Locale.ROOT);
        UserEntity user = userRepository.findWithRolesByEmail(normalizedStarter)
                .orElseGet(() -> userRepository.findAll().stream()
                        .filter(candidate -> normalizedStarter.equals(CamundaIdentitySyncService.camundaUserId(candidate)))
                        .findFirst()
                        .orElseThrow(() -> new CamundaFormValidationException("Application user is not synced for Camunda user: " + starterUserId)));
        if (requiredRole != null && !hasRole(user, requiredRole)) {
            throw new CamundaFormValidationException("Only " + requiredRole + " users can run this Camunda process");
        }
        if (!user.isEnabled()) {
            throw new CamundaFormValidationException("User is disabled: " + starterUserId);
        }
        return user;
    }

    public UserEntity resolvePermissionUser(String starterUserId, UUID explicitUserId) {
        try {
            if (explicitUserId != null) {
                return userRepository.findById(explicitUserId).orElse(null);
            }
            return resolveUserFromCamundaStarter(starterUserId, null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String primaryRole(UserEntity user) {
        if (user == null) {
            return "UNKNOWN";
        }
        if (hasRole(user, "ADMIN")) {
            return "ADMIN";
        }
        if (hasRole(user, "RECRUITER")) {
            return "RECRUITER";
        }
        if (hasRole(user, "CANDIDATE")) {
            return "CANDIDATE";
        }
        return "UNKNOWN";
    }

    public boolean hasRole(UserEntity user, String roleCode) {
        return user.getRoles().stream().anyMatch(role -> roleCode.equalsIgnoreCase(role.getCode()));
    }
}
