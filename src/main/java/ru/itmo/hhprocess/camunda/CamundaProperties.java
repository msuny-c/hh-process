package ru.itmo.hhprocess.camunda;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.camunda")
public class CamundaProperties {
    private boolean enabled;
    private String baseUrl = "http://localhost:8080/engine-rest";
    private String username;
    private String password;
    private String workerId = "hh-process-api";
    private String processKey = "candidate-application-process";
    private String vacancyProcessKey = "vacancy-management-process";
    private String cancelInterviewProcessKey = "cancel-interview-process";
    private String screeningMessageName = "ScreeningCompleted";
    private String recruiterReviewTaskKey = "recruiter-review";
    private String candidateResponseTaskKey = "candidate-response";
    private int maxTasks = 8;
    private long lockDurationMs = 30000;
    private long fetchIntervalMs = 5000;
    private long initialDelayMs = 5000;
    private int failureRetries = 3;
    private long failureRetryTimeoutMs = 15000;
}
