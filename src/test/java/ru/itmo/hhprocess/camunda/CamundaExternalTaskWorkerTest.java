package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.config.CamundaProperties;

import ru.itmo.hhprocess.exception.CamundaFormValidationException;

import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CamundaExternalTaskWorkerTest {

    @Test
    @SuppressWarnings("unchecked")
    void formValidationBpmnErrorCarriesMessageAndFieldNamesBackToUserTask() throws Exception {
        UUID applicationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ExternalTask task = mock(ExternalTask.class);
        when(task.getVariable("applicationId")).thenReturn(applicationId.toString());
        when(task.getId()).thenReturn("external-task-1");

        ExternalTaskService service = mock(ExternalTaskService.class);

        CamundaExternalTaskWorker worker = new CamundaExternalTaskWorker(null, null, null, new CamundaProperties());

        Method method = CamundaExternalTaskWorker.class.getDeclaredMethod(
                "throwFormValidationBpmnError",
                ExternalTaskService.class,
                ExternalTask.class,
                CamundaFormValidationException.class);
        method.setAccessible(true);

        boolean routed = (boolean) method.invoke(
                worker,
                service,
                task,
                new CamundaFormValidationException("Resume text", "Resume text is required"));

        assertTrue(routed);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(service).handleBpmnError(
                org.mockito.Mockito.eq(task), codeCaptor.capture(), msgCaptor.capture(), varsCaptor.capture());

        assertEquals("FORM_VALIDATION_FAILED", codeCaptor.getValue());
        assertEquals("Resume text is required", msgCaptor.getValue());

        Map<?, ?> vars = varsCaptor.getValue();
        assertEquals(applicationId.toString(), vars.get("applicationId"));
        assertEquals("Resume text is required", vars.get("formErrorMessage"));
        assertEquals("Resume text", vars.get("formErrorField"));
        assertEquals("Resume text", vars.get("formErrorFields"));
        assertEquals("FORM_VALIDATION_FAILED", vars.get("formErrorCode"));
    }
}
