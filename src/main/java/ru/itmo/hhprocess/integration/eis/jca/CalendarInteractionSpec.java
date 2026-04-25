package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.cci.InteractionSpec;
import java.io.Serial;

public class CalendarInteractionSpec implements InteractionSpec {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String operation;

    private CalendarInteractionSpec(String operation) {
        this.operation = operation;
    }

    public static CalendarInteractionSpec create() {
        return new CalendarInteractionSpec("CREATE");
    }

    public static CalendarInteractionSpec cancel() {
        return new CalendarInteractionSpec("CANCEL");
    }

    public String getOperation() {
        return operation;
    }
}
