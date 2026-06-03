package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaAuthorizationService {

    private static final String GROUP_CANDIDATE = "CANDIDATE";
    private static final String GROUP_RECRUITER = "RECRUITER";
    private static final String GROUP_ADMIN = "ADMIN";

    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public void configureStartAuthorizations() {
        if (!properties.isEnabled()) {
            return;
        }

        ensureGroup(GROUP_CANDIDATE, "Candidates");
        ensureGroup(GROUP_RECRUITER, "Recruiters");
        ensureGroup(GROUP_ADMIN, "Administrators");

        grantStart(GROUP_CANDIDATE, properties.getApplicationProcessKey());
        grantStart(GROUP_RECRUITER, properties.getVacancyProcessKey());

        for (String processKey : List.of(
                properties.getApplicationProcessKey(),
                properties.getVacancyProcessKey(),
                properties.getAdminInterviewResetProcessKey(),
                properties.getTimeoutSchedulerProcessKey())) {
            grantStart(GROUP_ADMIN, processKey);
        }
    }

    private void ensureGroup(String groupId, String groupName) {
        if (camundaRestClient.ensureGroupExists(groupId, groupName)) {
            log.info("Camunda group is available: {}", groupId);
        }
    }

    private void grantStart(String groupId, String processKey) {
        if (camundaRestClient.ensureProcessStartAuthorization(groupId, processKey)) {
            log.info("Camunda start authorization is available: group={}, processKey={}", groupId, processKey);
        }
    }
}
