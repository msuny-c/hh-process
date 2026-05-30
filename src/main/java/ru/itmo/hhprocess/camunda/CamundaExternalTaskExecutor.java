package ru.itmo.hhprocess.camunda;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.config.ApiRoleOnly;

@Slf4j
@Component
@ApiRoleOnly
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CamundaExternalTaskExecutor {
    private final CamundaRestClient camundaRestClient;
    private final Map<String, CamundaExternalTaskHandler> handlers;
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);

    public CamundaExternalTaskExecutor(CamundaRestClient camundaRestClient, List<CamundaExternalTaskHandler> handlers) {
        this.camundaRestClient = camundaRestClient;
        this.handlers = handlers.stream().collect(Collectors.toMap(CamundaExternalTaskHandler::topic, Function.identity(), (a, b) -> a, ConcurrentHashMap::new));
    }

    @Scheduled(fixedDelayString = "${app.camunda.fetch-interval-ms:5000}", initialDelayString = "${app.camunda.initial-delay-ms:5000}")
    public void poll() {
        if (!runInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            List<CamundaExternalTask> tasks = camundaRestClient.fetchAndLock(handlers.keySet().stream().toList());
            for (CamundaExternalTask task : tasks) {
                execute(task);
            }
        } finally {
            runInProgress.set(false);
        }
    }

    private void execute(CamundaExternalTask task) {
        CamundaExternalTaskHandler handler = handlers.get(task.topicName());
        if (handler == null) {
            return;
        }
        try {
            Map<String, Object> result = handler.handle(task);
            camundaRestClient.complete(task.id(), result == null ? Map.of() : result);
        } catch (Exception e) {
            log.error("Camunda external task {} failed", task.id(), e);
            camundaRestClient.fail(task.id(), e);
        }
    }
}
