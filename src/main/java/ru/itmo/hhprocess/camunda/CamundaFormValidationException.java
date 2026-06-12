package ru.itmo.hhprocess.camunda;

public class CamundaFormValidationException extends RuntimeException {

    private final String fieldName;

    public CamundaFormValidationException(String message) {
        super(message);
        this.fieldName = "";
    }

    public CamundaFormValidationException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName == null ? "" : fieldName.trim();
    }

    public String getFieldName() {
        return fieldName;
    }
}
