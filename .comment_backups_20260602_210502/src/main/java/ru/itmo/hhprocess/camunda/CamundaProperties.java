package ru.itmo.hhprocess.camunda;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.camunda")
public class CamundaProperties {

    /** Enables integration with standalone Camunda 7 Run service through REST API. */
    private boolean enabled = true;

    /** Camunda 7 engine REST endpoint, for example http://camunda:8080/engine-rest. */
    private String baseUrl = "http://camunda:8080/engine-rest";

    /** Optional HTTP Basic username for Camunda REST API. Empty means no auth header. */
    private String username = "";

    /** Optional HTTP Basic password for Camunda REST API. Empty means no auth header. */
    private String password = "";

    /** Do not break application endpoints if Camunda is temporarily unavailable. */
    private boolean failOnError = false;

    /** Deployment name visible in Camunda Cockpit. */
    private String deploymentName = "hh-process-bpmn";

    private String applicationProcessKey = "hhApplicationProcess";
    private String vacancyProcessKey = "hhVacancyProcess";
    private String timeoutSchedulerProcessKey = "hhTimeoutSchedulerProcess";

    private Worker worker = new Worker();

    @Getter
    @Setter
    public static class Worker {
        private boolean enabled = true;
        private String id = "hh-process-worker";
        private int maxTasks = 10;
        private long asyncResponseTimeoutMs = 2000;
        private long lockDurationMs = 30000;
        private long pollIntervalMs = 3000;
    }
}
