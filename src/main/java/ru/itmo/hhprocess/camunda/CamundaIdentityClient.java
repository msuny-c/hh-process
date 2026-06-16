package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class CamundaIdentityClient {

    private final CamundaRestClient restClient;

    public boolean ensureGroupExists(String groupId, String groupName) {
        return restClient.ensureGroupExists(groupId, groupName);
    }

    public boolean ensureGroupExists(String groupId, String groupName, String groupType) {
        return restClient.ensureGroupExists(groupId, groupName, groupType);
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName, String initialPassword) {
        return restClient.ensureUserExists(userId, email, firstName, lastName, initialPassword);
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName,
                                    String initialPassword, boolean updatePassword) {
        return restClient.ensureUserExists(userId, email, firstName, lastName, initialPassword, updatePassword);
    }

    public boolean ensureMembershipExists(String userId, String groupId) {
        return restClient.ensureMembershipExists(userId, groupId);
    }

    public Set<String> findMembershipGroupIds(String userId) {
        return restClient.findMembershipGroupIds(userId);
    }

    public boolean removeMembershipIfExists(String userId, String groupId) {
        return restClient.removeMembershipIfExists(userId, groupId);
    }
}
