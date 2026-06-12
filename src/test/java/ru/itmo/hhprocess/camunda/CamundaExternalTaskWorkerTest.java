package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamundaExternalTaskWorkerTest {

    @Test
    void formValidationBpmnErrorCarriesMessageAndFieldNamesBackToUserTask() throws Exception {
        CapturingCamundaRestClient camundaRestClient = new CapturingCamundaRestClient();
        CamundaExternalTaskWorker worker = new CamundaExternalTaskWorker(camundaRestClient, null, null);
        UUID applicationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Map<String, Object> task = Map.of(
                "variables", Map.of("applicationId", CamundaVariable.variable(applicationId))
        );

        Method method = CamundaExternalTaskWorker.class.getDeclaredMethod(
                "throwFormValidationBpmnError",
                String.class,
                Map.class,
                CamundaFormValidationException.class);
        method.setAccessible(true);

        boolean routed = (boolean) method.invoke(
                worker,
                "external-task-1",
                task,
                new CamundaFormValidationException("Resume text", "Resume text is required"));

        assertTrue(routed);
        assertEquals("external-task-1", camundaRestClient.externalTaskId);
        assertEquals("FORM_VALIDATION_FAILED", camundaRestClient.errorCode);
        assertEquals("Resume text is required", camundaRestClient.message);
        assertEquals(applicationId.toString(), camundaRestClient.variables.get("applicationId"));
        assertEquals("Resume text is required", camundaRestClient.variables.get("formErrorMessage"));
        assertEquals("Resume text", camundaRestClient.variables.get("formErrorField"));
        assertEquals("Resume text", camundaRestClient.variables.get("formErrorFields"));
        assertEquals("FORM_VALIDATION_FAILED", camundaRestClient.variables.get("formErrorCode"));
    }

    private static class CapturingCamundaRestClient extends CamundaRestClient {
        private String externalTaskId;
        private String errorCode;
        private String message;
        private Map<String, ?> variables;

        CapturingCamundaRestClient() {
            super(null, new CamundaProperties());
        }

        @Override
        public boolean throwBpmnErrorExternalTask(
                String externalTaskId,
                String errorCode,
                String message,
                Map<String, ?> variables) {
            this.externalTaskId = externalTaskId;
            this.errorCode = errorCode;
            this.message = message;
            this.variables = variables;
            return true;
        }
    }
}
