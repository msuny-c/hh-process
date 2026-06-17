package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.config.CamundaProperties;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaAuthorizationServiceTest {

    @Test
    void grantsProcessInstanceCreateAndBusinessAdminMembership() {
        RecordingCamundaRestClient client = new RecordingCamundaRestClient();
        CamundaProperties properties = new CamundaProperties();
        properties.setEnabled(true);

        new CamundaAuthorizationService(client, properties).configureStartAuthorizations();

        assertTrue(client.memberships.contains("admin:ADMIN"));
        assertTrue(client.authorizations.contains("CANDIDATE:8:*:CREATE"));
        assertTrue(client.authorizations.contains("RECRUITER:8:*:CREATE"));
        assertTrue(client.authorizations.contains("ADMIN:8:*:CREATE,READ,UPDATE"));
        assertTrue(client.authorizations.contains("camunda-admin:8:*:CREATE,READ,UPDATE"));
    }

    private static class RecordingCamundaRestClient extends CamundaRestClient {
        private final List<String> memberships = new ArrayList<>();
        private final List<String> authorizations = new ArrayList<>();

        RecordingCamundaRestClient() {
            super(null, new CamundaProperties());
        }

        @Override
        public boolean ensureGroupExists(String groupId, String groupName) {
            return true;
        }

        @Override
        public boolean ensureGroupExists(String groupId, String groupName, String groupType) {
            return true;
        }

        @Override
        public boolean ensureUserExists(String userId, String email, String firstName, String lastName,
                                        String initialPassword, boolean resetPassword) {
            return true;
        }

        @Override
        public boolean ensureMembershipExists(String userId, String groupId) {
            memberships.add(userId + ":" + groupId);
            return true;
        }

        @Override
        public boolean deleteGroupAuthorization(String groupId, int resourceType, String resourceId) {
            return true;
        }

        @Override
        public boolean ensureProcessStartAuthorization(String groupId, String processDefinitionKey) {
            authorizations.add(groupId + ":6:" + processDefinitionKey + ":CREATE_INSTANCE,READ");
            return true;
        }

        @Override
        public boolean ensureGroupAuthorization(String groupId, int resourceType, String resourceId, List<String> permissions) {
            authorizations.add(groupId + ":" + resourceType + ":" + resourceId + ":" + String.join(",", permissions));
            return true;
        }
    }
}
