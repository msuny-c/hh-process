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
        Map<String, ?> query = GROUP_CANDIDATE.equals(groupId)
                ? Map.of("active", true, "assigneeExpression", "${currentUser()}")
                : Map.of("active", true, "candidateGroup", groupId);
        recreateFilter(
                name,
                query,
                Map.of("description", "Активные задачи группы " + groupId, "priority", 10),
                List.of(groupId)
        );
    }

    private void createSharedFilter(String name) {
        recreateFilter(
                name,
                Map.of("active", true, "assigneeExpression", "${currentUser()}"),
                Map.of("description", "Активные задачи, назначенные текущему пользователю", "priority", 20),
                List.of(GROUP_CANDIDATE, GROUP_RECRUITER, GROUP_ADMIN)
        );
    }

    private void recreateFilter(String name, Map<String, ?> query, Map<String, ?> filterProperties, List<String> groups) {
        List<String> existingIds = camundaRestClient.findFilterIdsByName(name);
        String filterId = existingIds.isEmpty() ? "" : existingIds.get(0);
        existingIds.stream().skip(1).forEach(camundaRestClient::deleteFilter);
        if (filterId.isBlank()) {
            filterId = camundaRestClient.createTaskFilter(name, query, filterProperties).orElse("");
        } else {
            camundaRestClient.updateTaskFilter(filterId, name, query, filterProperties);
        }
        if (!filterId.isBlank()) {
            for (String group : groups) {
                camundaRestClient.ensureFilterReadAuthorization(group, filterId);
            }
            log.info("Configured Camunda Tasklist filter: name={}, id={}", name, filterId);
        }
    }
}
