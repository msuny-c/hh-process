package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CamundaAuthorizationClient {

    private final CamundaRestClient restClient;

    public boolean ensureProcessStartAuthorization(String groupId, String processDefinitionKey) {
        return restClient.ensureProcessStartAuthorization(groupId, processDefinitionKey);
    }

    public boolean ensureFilterReadAuthorization(String groupId, String filterId) {
        return restClient.ensureFilterReadAuthorization(groupId, filterId);
    }

    public boolean deleteGroupAuthorization(String groupId, int resourceType, String resourceId) {
        return restClient.deleteGroupAuthorization(groupId, resourceType, resourceId);
    }

    public boolean ensureGroupAuthorization(String groupId, int resourceType, String resourceId, List<String> permissions) {
        return restClient.ensureGroupAuthorization(groupId, resourceType, resourceId, permissions);
    }

    public List<String> findFilterIdsByName(String name) {
        return restClient.findFilterIdsByName(name);
    }

    public List<String> findFilterIdsByOwner(String owner) {
        return restClient.findFilterIdsByOwner(owner);
    }

    public void deleteFilter(String filterId) {
        restClient.deleteFilter(filterId);
    }

    public Optional<String> createTaskFilter(String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        return restClient.createTaskFilter(name, query, filterProperties);
    }

    public boolean updateTaskFilter(String filterId, String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        return restClient.updateTaskFilter(filterId, name, query, filterProperties);
    }
}
