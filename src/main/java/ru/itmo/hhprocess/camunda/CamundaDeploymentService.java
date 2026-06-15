package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.CamundaProperties;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaDeploymentService {

    private final CamundaRestClient camundaRestClient;
    private final CamundaAuthorizationService camundaAuthorizationService;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final CamundaTasklistFilterService camundaTasklistFilterService;
    private final CamundaProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void deployOnStartup() {
        if (!properties.isEnabled()) {
            log.info("Camunda integration is disabled; BPMN deployment skipped");
            return;
        }

        Map<String, Resource> resources = scanDeploymentResources();
        camundaRestClient.deploy(properties.getDeploymentName(), resources)
                .ifPresent(id -> log.info("Deployed BPMN/resources to Camunda deploymentId={}", id));
        camundaAuthorizationService.configureStartAuthorizations();
        camundaIdentitySyncService.syncUsersGroupsAndMemberships();
        camundaTasklistFilterService.configureTasklistFilters();
        if (!camundaRestClient.hasActiveProcessInstance(properties.getTimeoutSchedulerProcessKey(), "timeout-scheduler")) {
            camundaRestClient.startProcessByKey(properties.getTimeoutSchedulerProcessKey(), "timeout-scheduler",
                    Map.of("startedAt", Instant.now()));
        }
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
