package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.RoleEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.UserRepository;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaIdentitySyncService {

    private final UserRepository userRepository;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    @Transactional(readOnly = true)
    public void syncUsersGroupsAndMemberships() {
        if (!properties.isEnabled()) {
            return;
        }

        int syncedUsers = 0;
        int syncedMemberships = 0;
        for (UserEntity user : userRepository.findAll()) {
            if (!user.isEnabled()) {
                continue;
            }
            SyncResult result = syncUser(user);
            if (result.userSynced()) {
                syncedUsers++;
            }
            syncedMemberships += result.membershipsSynced();
        }
        log.info("Camunda identity sync finished: users={}, memberships={}", syncedUsers, syncedMemberships);
    }

    @Transactional(readOnly = true)
    public void syncUserById(UUID userId) {
        if (!properties.isEnabled()) {
            return;
        }
        userRepository.findById(userId)
                .filter(UserEntity::isEnabled)
                .ifPresent(this::syncUser);
    }

    public SyncResult syncUser(UserEntity user) {
        return syncUser(user, properties.getIdentitySyncInitialPassword(), false);
    }

    public SyncResult syncUserWithPassword(UserEntity user, String plainPassword) {
        String password = (plainPassword == null || plainPassword.isBlank())
                ? properties.getIdentitySyncInitialPassword()
                : plainPassword;
        return syncUser(user, password, true);
    }

    private SyncResult syncUser(UserEntity user, String camundaPassword, boolean updatePasswordWhenExists) {
        String camundaUserId = camundaUserId(user);
        boolean userSynced = camundaRestClient.ensureUserExists(
                camundaUserId,
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                camundaPassword,
                updatePasswordWhenExists);
        Set<String> desiredGroups = new LinkedHashSet<>();
        for (RoleEntity role : user.getRoles()) {
            desiredGroups.add(normalizeGroup(role.getCode()));
        }

        int membershipsSynced = 0;
        for (String groupId : desiredGroups) {
            if (camundaRestClient.ensureGroupExists(groupId, groupId)
                    && camundaRestClient.ensureMembershipExists(camundaUserId, groupId)) {
                membershipsSynced++;
            }
        }

        for (String existingGroup : camundaRestClient.findMembershipGroupIds(camundaUserId)) {
            if (isApplicationRoleGroup(existingGroup) && !desiredGroups.contains(existingGroup)) {
                camundaRestClient.removeMembershipIfExists(camundaUserId, existingGroup);
            }
        }
        return new SyncResult(userSynced, membershipsSynced);
    }

    private boolean isApplicationRoleGroup(String groupId) {
        return "CANDIDATE".equals(groupId) || "RECRUITER".equals(groupId) || "ADMIN".equals(groupId);
    }

    static String camundaUserId(UserEntity user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return "user" + user.getId().toString().replace("-", "");
        }
        String normalizedEmail = user.getEmail().trim().toLowerCase(Locale.ROOT);
        String candidate = normalizedEmail.replaceAll("[^a-z0-9]", "");
        if (candidate.isBlank()) {
            return "user" + user.getId().toString().replace("-", "");
        }
        return candidate;
    }

    private String normalizeGroup(String roleCode) {
        return roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
    }

    public record SyncResult(boolean userSynced, int membershipsSynced) {
    }
}
