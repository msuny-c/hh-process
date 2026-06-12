package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaDeploymentService {

    private static final Set<String> DEFAULT_DEMO_PROCESS_KEYS = Set.of("invoice", "ReviewInvoice");
    private static final List<String> DEFAULT_DEMO_FILTER_NAMES = List.of(
            "Accounting",
            "All Tasks",
            "All tasks",
            "John's Tasks",
            "Mary's Tasks",
            "My Group Tasks",
            "My Tasks",
            "Peter's Tasks");

    private final CamundaRestClient camundaRestClient;
    private final CamundaWorkflowFacade camundaWorkflowFacade;
    private final CamundaIdentityProviderService camundaIdentityProviderService;
    private final CamundaProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void deployOnStartup() {
        if (!properties.isEnabled()) {
            log.info("Camunda integration is disabled; BPMN deployment skipped");
            return;
        }

        waitForCamunda();

        cleanupDefaultDemoArtifacts();

        Map<String, Resource> resources = scanDeploymentResources();

        camundaRestClient.deploy(properties.getDeploymentName(), resources)
                .ifPresent(id -> log.info("Deployed BPMN/resources to Camunda deploymentId={}", id));
        camundaIdentityProviderService.provisionApplicationIdentity();
        cleanupDefaultDemoArtifactsWithRetries();
        camundaWorkflowFacade.startTimeoutSchedulerIfNeeded();
    }

    private void cleanupDefaultDemoArtifacts() {
        for (String processKey : DEFAULT_DEMO_PROCESS_KEYS) {
            for (String deploymentId : camundaRestClient.findDeploymentIdsByProcessDefinitionKey(processKey)) {
                camundaRestClient.deleteDeploymentCascade(deploymentId);
                log.info("Deleted default Camunda demo deployment: processKey={}, deploymentId={}", processKey, deploymentId);
            }
        }
        for (String filterName : DEFAULT_DEMO_FILTER_NAMES) {
            for (String filterId : camundaRestClient.findFilterIdsByName(filterName)) {
                camundaRestClient.deleteFilter(filterId);
                log.info("Deleted default Camunda demo Tasklist filter: name={}, filterId={}", filterName, filterId);
            }
        }
        for (String filterId : camundaRestClient.findFilterIdsByOwner("demo")) {
            camundaRestClient.deleteFilter(filterId);
            log.info("Deleted default Camunda demo Tasklist filter: owner=demo, filterId={}", filterId);
        }
    }

    private void cleanupDefaultDemoArtifactsWithRetries() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            cleanupDefaultDemoArtifacts();
            if (camundaRestClient.findFilterIdsByOwner("demo").isEmpty()) {
                log.info("Default Camunda demo artifact cleanup finished; attempt={}", attempt);
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Default Camunda demo Tasklist filters are still present after cleanup retries");
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

    Map<String, Resource> scanDeploymentResources() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Resource> resources = new LinkedHashMap<>();
        addAll(resources, resolver, "classpath*:camunda/*.bpmn");
        addAll(resources, resolver, "classpath*:camunda/*.dmn");
        addAll(resources, resolver, "classpath*:camunda/forms/*.form");
        log.info("Scanned Camunda deployment resources: {}", resources.keySet());
        return resources;
    }

    private void addAll(Map<String, Resource> resources, ResourcePatternResolver resolver, String pattern) {
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                if (resource.exists() && resource.isReadable() && resource.getFilename() != null) {
                    resources.put(resource.getFilename(), resource);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan Camunda resources by pattern: " + pattern, e);
        }
    }
}
