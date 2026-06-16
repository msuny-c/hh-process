package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CamundaDeploymentClient {

    private final CamundaRestClient restClient;

    public Optional<String> deploy(String deploymentName, Map<String, Resource> resources) {
        return restClient.deploy(deploymentName, resources);
    }

    public List<String> findDeploymentIdsByProcessDefinitionKey(String processDefinitionKey) {
        return restClient.findDeploymentIdsByProcessDefinitionKey(processDefinitionKey);
    }

    public void deleteDeploymentCascade(String deploymentId) {
        restClient.deleteDeploymentCascade(deploymentId);
    }

    public boolean isAvailable() {
        return restClient.isAvailable();
    }
}
