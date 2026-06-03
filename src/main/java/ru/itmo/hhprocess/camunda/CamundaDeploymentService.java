package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaDeploymentService {

    private final CamundaRestClient camundaRestClient;
    private final CamundaWorkflowFacade camundaWorkflowFacade;
    private final CamundaAuthorizationService camundaAuthorizationService;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final CamundaProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void deployOnStartup() {
        if (!properties.isEnabled()) {
            log.info("Camunda integration is disabled; BPMN deployment skipped");
            return;
        }

        waitForCamunda();

        Map<String, Resource> resources = new LinkedHashMap<>();
        add(resources, "hh-application-process.bpmn", "camunda/hh-application-process.bpmn");
        add(resources, "hh-vacancy-process.bpmn", "camunda/hh-vacancy-process.bpmn");
        add(resources, "hh-timeout-scheduler.bpmn", "camunda/hh-timeout-scheduler.bpmn");
        add(resources, "hh-admin-interview-reset.bpmn", "camunda/hh-admin-interview-reset.bpmn");
        add(resources, "hh-process-overview.bpmn", "camunda/hh-process-overview.bpmn");
        add(resources, "apply-to-vacancy.form", "camunda/forms/apply-to-vacancy.form");
        add(resources, "create-vacancy.form", "camunda/forms/create-vacancy.form");
        add(resources, "recruiter-decision.form", "camunda/forms/recruiter-decision.form");
        add(resources, "invitation.form", "camunda/forms/invitation.form");
        add(resources, "invitation-response.form", "camunda/forms/invitation-response.form");
        add(resources, "close-vacancy.form", "camunda/forms/close-vacancy.form");
        add(resources, "reset-interview.form", "camunda/forms/reset-interview.form");
        add(resources, "application-result.form", "camunda/forms/application-result.form");
        add(resources, "vacancy-result.form", "camunda/forms/vacancy-result.form");
        add(resources, "admin-reset-result.form", "camunda/forms/admin-reset-result.form");

        camundaRestClient.deploy(properties.getDeploymentName(), resources)
                .ifPresent(id -> log.info("Deployed BPMN/resources to Camunda deploymentId={}", id));
        camundaAuthorizationService.configureStartAuthorizations();
        camundaIdentitySyncService.syncUsersGroupsAndMemberships();
        camundaWorkflowFacade.startTimeoutSchedulerIfNeeded();
    }

    private void waitForCamunda() {
        for (int attempt = 1; attempt <= 30; attempt++) {
            if (camundaRestClient.isAvailable()) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("Waiting for Camunda REST API; attempt={}/30", attempt);
        }
        log.warn("Camunda REST API is still unavailable; deployment attempt will be best-effort");
    }

    private static void add(Map<String, Resource> resources, String deploymentName, String classpathLocation) {
        resources.put(deploymentName, new ClassPathResource(classpathLocation));
    }
}
