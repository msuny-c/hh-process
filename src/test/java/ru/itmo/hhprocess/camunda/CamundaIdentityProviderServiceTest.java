package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CamundaIdentityProviderServiceTest {

    @Test
    void provisionsGroupsUsersMembershipsAndTasklistFiltersAsOneIdentityProviderStep() {
        List<String> calls = new ArrayList<>();
        CamundaIdentityProviderService service = new CamundaIdentityProviderService(
                new RecordingAuthorizationService(calls),
                new RecordingIdentitySyncService(calls),
                new RecordingTasklistFilterService(calls));

        service.provisionApplicationIdentity();

        assertEquals(List.of(
                "start-authorizations",
                "users-groups-memberships",
                "tasklist-filters"), calls);
    }

    private static class RecordingAuthorizationService extends CamundaAuthorizationService {
        private final List<String> calls;

        RecordingAuthorizationService(List<String> calls) {
            super(null, new CamundaProperties());
            this.calls = calls;
        }

        @Override
        public void configureStartAuthorizations() {
            calls.add("start-authorizations");
        }
    }

    private static class RecordingIdentitySyncService extends CamundaIdentitySyncService {
        private final List<String> calls;

        RecordingIdentitySyncService(List<String> calls) {
            super(null, null, new CamundaProperties());
            this.calls = calls;
        }

        @Override
        public void syncUsersGroupsAndMemberships() {
            calls.add("users-groups-memberships");
        }
    }

    private static class RecordingTasklistFilterService extends CamundaTasklistFilterService {
        private final List<String> calls;

        RecordingTasklistFilterService(List<String> calls) {
            super(null, new CamundaProperties());
            this.calls = calls;
        }

        @Override
        public void configureTasklistFilters() {
            calls.add("tasklist-filters");
        }
    }
}
