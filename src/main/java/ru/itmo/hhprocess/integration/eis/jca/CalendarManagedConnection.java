package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.NotSupportedException;
import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionEventListener;
import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.LocalTransaction;
import jakarta.resource.spi.ManagedConnection;
import jakarta.resource.spi.ManagedConnectionMetaData;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;

public class CalendarManagedConnection implements ManagedConnection {

    private final CalendarManagedConnectionFactory managedConnectionFactory;
    private final List<ConnectionEventListener> listeners = new ArrayList<>();
    private PrintWriter logWriter;

    public CalendarManagedConnection(CalendarManagedConnectionFactory managedConnectionFactory) {
        this.managedConnectionFactory = managedConnectionFactory;
    }

    @Override
    public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) {
        return new CalendarConnection(this);
    }

    @Override
    public void destroy() {
        listeners.clear();
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void associateConnection(Object connection) throws ResourceException {
        if (!(connection instanceof CalendarConnection calendarConnection)) {
            throw new ResourceException("Unsupported connection type");
        }
        calendarConnection.setManagedConnection(this);
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public XAResource getXAResource() throws ResourceException {
        throw new NotSupportedException("XA is not supported for mock calendar adapter");
    }

    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        throw new NotSupportedException("Local transactions are not supported");
    }

    @Override
    public ManagedConnectionMetaData getMetaData() {
        return new ManagedConnectionMetaData() {
            @Override
            public String getEISProductName() {
                return "Corporate Interview Calendar";
            }

            @Override
            public String getEISProductVersion() {
                return "1.0";
            }

            @Override
            public int getMaxConnections() {
                return 10;
            }

            @Override
            public String getUserName() {
                return "system";
            }
        };
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    CalendarMappedRecord execute(CalendarInteractionSpec spec, CalendarMappedRecord input) throws ResourceException {
        return managedConnectionFactory.execute(spec, input);
    }
}
