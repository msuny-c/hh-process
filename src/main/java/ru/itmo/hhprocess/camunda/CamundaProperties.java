package ru.itmo.hhprocess.camunda;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.camunda")
public class CamundaProperties {

    private boolean enabled = true;

    private String baseUrl = "http://camunda:8080/engine-rest";

    private String username = "";

    private String password = "";

    private boolean failOnError = false;

    private String deploymentName = "hh-process-bpmn";

    private String applicationProcessKey = "hhApplicationProcess";
    private String vacancyProcessKey = "hhVacancyProcess";
    private String timeoutSchedulerProcessKey = "hhTimeoutSchedulerProcess";
    private String adminInterviewResetProcessKey = "hhAdminInterviewResetProcess";

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
