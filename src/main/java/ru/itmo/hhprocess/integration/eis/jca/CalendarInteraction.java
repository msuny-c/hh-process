package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.ResourceException;
import jakarta.resource.cci.Connection;
import jakarta.resource.cci.Interaction;
import jakarta.resource.cci.InteractionSpec;
import jakarta.resource.cci.Record;
import jakarta.resource.cci.ResourceWarning;

public class CalendarInteraction implements Interaction, AutoCloseable {

    private final CalendarConnection connection;
    private final CalendarManagedConnection managedConnection;

    public CalendarInteraction(CalendarConnection connection, CalendarManagedConnection managedConnection) {
        this.connection = connection;
        this.managedConnection = managedConnection;
    }

    @Override
    public void close() {
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean execute(InteractionSpec ispec, Record input, Record output) throws ResourceException {
        CalendarMappedRecord result = executeInternal(ispec, input);
        if (output instanceof CalendarMappedRecord mappedOutput) {
            mappedOutput.clear();
            mappedOutput.putAll(result);
            return true;
        }
        return false;
    }

    @Override
    public Record execute(InteractionSpec ispec, Record input) throws ResourceException {
        return executeInternal(ispec, input);
    }

    private CalendarMappedRecord executeInternal(InteractionSpec ispec, Record input) throws ResourceException {
        if (!(ispec instanceof CalendarInteractionSpec spec)) {
            throw new ResourceException("Unsupported interaction spec");
        }
        if (!(input instanceof CalendarMappedRecord mappedRecord)) {
            throw new ResourceException("Unsupported record type");
        }
        return managedConnection.execute(spec, mappedRecord);
    }

    @Override
    public ResourceWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
    }
}
