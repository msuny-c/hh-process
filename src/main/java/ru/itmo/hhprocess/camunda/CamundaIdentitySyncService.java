package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.entity.RoleEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.repository.UserRepository;

import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaIdentitySyncService {

    private final UserRepository userRepository;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public static String camundaUserId(UserEntity user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return "user" + user.getId().toString().replace("-", "");
        }
        String normalized = user.getEmail().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isBlank() ? "user" + user.getId().toString().replace("-", "") : normalized;
    }

    @Transactional(readOnly = true)
    public UserEntity resolveUserFromCamundaStarter(String starterUserId, String requiredRole) {
        UserEntity user = userRepository.findWithRolesByEmail(starterUserId.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new CamundaFormValidationException("Unknown Camunda user: " + starterUserId));
        if (requiredRole != null && !hasRole(user, requiredRole)) {
            throw new CamundaFormValidationException("Only " + requiredRole + " users can run this process");
        }
        return user;
    }

    public UserEntity resolveRecruiterForVacancyCommand(String starterUserId, UUID recruiterUserId) {
        if (recruiterUserId != null) {
            return userRepository.findById(recruiterUserId)
                    .orElseThrow(() -> new CamundaFormValidationException("Recruiter not found: " + recruiterUserId));
        }
        return resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
    }

    public boolean hasRole(UserEntity user, String roleCode) {
        return user.getRoles().stream().anyMatch(role -> roleCode.equalsIgnoreCase(role.getCode()));
    }

    @Transactional(readOnly = true)
    public void syncUsersGroupsAndMemberships() {
        if (!properties.isEnabled()) {
            return;
        }
        userRepository.findAllWithRolesBy().stream()
                .filter(UserEntity::isEnabled)
                .forEach(this::syncUser);
        log.info("Camunda identity sync finished");
    }

    public void syncUserById(UUID userId) {
        if (properties.isEnabled()) {
            userRepository.findWithRolesById(userId).filter(UserEntity::isEnabled).ifPresent(this::syncUser);
        }
    }

    public void syncUserWithPassword(UserEntity user, String plainPassword) {
        syncUser(user, plainPassword == null || plainPassword.isBlank()
                ? properties.getIdentitySyncInitialPassword()
                : plainPassword);
    }

    private void syncUser(UserEntity user) {
        syncUser(user, properties.getIdentitySyncInitialPassword());
    }

    private void syncUser(UserEntity user, String password) {
        String id = camundaUserId(user);
        camundaRestClient.ensureUserExists(id, user.getEmail(), user.getFirstName(), user.getLastName(), password, true);
        for (RoleEntity role : user.getRoles()) {
            String group = role.getCode().toUpperCase(Locale.ROOT);
            camundaRestClient.ensureGroupExists(group, group);
            camundaRestClient.ensureMembershipExists(id, group);
        }
    }
}
