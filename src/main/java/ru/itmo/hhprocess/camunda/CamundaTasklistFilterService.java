package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaTasklistFilterService {

    private static final String GROUP_CANDIDATE = "CANDIDATE";
    private static final String GROUP_RECRUITER = "RECRUITER";
    private static final String GROUP_ADMIN = "ADMIN";

    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public void configureTasklistFilters() {
        if (!properties.isEnabled()) {
            return;
        }

        createRoleFilter("Задачи кандидата", GROUP_CANDIDATE);
        createRoleFilter("Задачи рекрутера", GROUP_RECRUITER);
        createRoleFilter("Задачи администратора", GROUP_ADMIN);
        createSharedFilter("Мои активные задачи");
    }

    private void createRoleFilter(String name, String groupId) {
        recreateFilter(
                name,
                Map.of("active", true, "candidateGroup", groupId),
                Map.of("description", "Активные задачи группы " + groupId, "priority", 10),
                List.of(groupId)
        );
    }

    private void createSharedFilter(String name) {
        recreateFilter(
                name,
                Map.of("active", true),
                Map.of("description", "Все активные задачи, доступные пользователю по правам Camunda", "priority", 20),
                List.of(GROUP_CANDIDATE, GROUP_RECRUITER, GROUP_ADMIN)
        );
    }

    private void recreateFilter(String name, Map<String, ?> query, Map<String, ?> filterProperties, List<String> groups) {
        camundaRestClient.findFilterIdsByName(name).forEach(camundaRestClient::deleteFilter);
        camundaRestClient.createTaskFilter(name, query, filterProperties).ifPresent(filterId -> {
            for (String group : groups) {
                camundaRestClient.ensureFilterReadAuthorization(group, filterId);
            }
            log.info("Configured Camunda Tasklist filter: name={}, id={}", name, filterId);
        });
    }
}
