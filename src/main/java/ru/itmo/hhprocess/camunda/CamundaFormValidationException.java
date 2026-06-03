package ru.itmo.hhprocess.camunda;

/**
 * Business validation error for variables submitted from Camunda Forms.
 * The external task worker maps it to BPMN error FORM_VALIDATION_FAILED,
 * so the process can return the user to the form instead of rolling back the whole application flow.
 */
public class CamundaFormValidationException extends RuntimeException {

    public CamundaFormValidationException(String message) {
        super(message);
    }
}
