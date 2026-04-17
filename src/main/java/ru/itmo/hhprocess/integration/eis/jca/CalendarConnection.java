package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.NotSupportedException;
import jakarta.resource.ResourceException;
import jakarta.resource.cci.Connection;
import jakarta.resource.cci.ConnectionMetaData;
import jakarta.resource.cci.Interaction;
import jakarta.resource.cci.LocalTransaction;
import jakarta.resource.cci.ResultSetInfo;

public class CalendarConnection implements Connection, AutoCloseable {

    private CalendarManagedConnection managedConnection;

    public CalendarConnection(CalendarManagedConnection managedConnection) {
        this.managedConnection = managedConnection;
    }

    @Override
    public Interaction createInteraction() {
        return new CalendarInteraction(this, managedConnection);
    }

    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        throw new NotSupportedException("Local transactions are not supported");
    }

    @Override
    public ConnectionMetaData getMetaData() {
        return new ConnectionMetaData() {
            @Override
            public String getEISProductName() {
                return "Corporate Interview Calendar";
            }

            @Override
            public String getEISProductVersion() {
                return "1.0";
            }

            @Override
            public String getUserName() {
                return "system";
            }
        };
    }

    @Override
    public ResultSetInfo getResultSetInfo() throws ResourceException {
        throw new NotSupportedException("ResultSetInfo is not supported");
    }

    @Override
    public void close() {
    }

    void setManagedConnection(CalendarManagedConnection managedConnection) {
        this.managedConnection = managedConnection;
    }
}
