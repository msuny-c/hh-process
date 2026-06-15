package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.exception.CamundaFormValidationException;

import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.engine.variable.VariableMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.itmo.hhprocess.camunda.worker.subscription.AbstractExternalTaskWorker;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CamundaExternalTaskWorkerTest {

    @Test
    void formValidationBpmnErrorCarriesMessageAndFieldNamesBackToUserTask() {
        ExternalTask externalTask = mock(ExternalTask.class);
        ExternalTaskService externalTaskService = mock(ExternalTaskService.class);
        UUID applicationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(externalTask.getId()).thenReturn("external-task-1");
        when(externalTask.getActivityId()).thenReturn("ValidateApplyToVacancyForm");
        when(externalTask.getVariable("applicationId")).thenReturn(applicationId.toString());

        boolean routed = AbstractExternalTaskWorker.handleFormValidationError(
                externalTask,
                externalTaskService,
                new CamundaFormValidationException("Resume text", "Resume text is required"));

        assertTrue(routed);

        ArgumentCaptor<VariableMap> variablesCaptor = ArgumentCaptor.forClass(VariableMap.class);
        verify(externalTaskService).handleBpmnError(
                eq(externalTask),
                eq(AbstractExternalTaskWorker.FORM_VALIDATION_FAILED),
                eq("Resume text is required"),
                variablesCaptor.capture());

        VariableMap variables = variablesCaptor.getValue();
        assertEquals(applicationId.toString(), variables.get("applicationId"));
        assertEquals("Resume text is required", variables.get("formErrorMessage"));
        assertEquals("Resume text", variables.get("formErrorField"));
        assertEquals("Resume text", variables.get("formErrorFields"));
        assertEquals(AbstractExternalTaskWorker.FORM_VALIDATION_FAILED, variables.get("formErrorCode"));
    }
}
