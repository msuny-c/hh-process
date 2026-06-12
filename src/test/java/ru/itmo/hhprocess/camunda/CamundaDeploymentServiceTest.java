package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaDeploymentServiceTest {

    @Test
    void scanDeploymentResourcesFindsAllBpmnAndForms() {
        CamundaDeploymentService service = new CamundaDeploymentService(
                null, null, null, null);

        var resources = service.scanDeploymentResources();

        assertTrue(resources.containsKey("hh-application-process.bpmn"));
        assertTrue(resources.containsKey("hh-vacancy-process.bpmn"));
        assertTrue(resources.containsKey("hh-ui-admin-timeout-review.bpmn"));
        assertTrue(resources.containsKey("hh-operation-permissions.dmn"));
        assertTrue(resources.containsKey("hh-auto-screening.dmn"));
        assertTrue(resources.containsKey("hh-status-transitions.dmn"));
        assertTrue(resources.containsKey("hh-notification-templates.dmn"));
        assertTrue(resources.containsKey("apply-to-vacancy.form"));
        assertTrue(resources.containsKey("ui-json-display.form"));
    }
}
