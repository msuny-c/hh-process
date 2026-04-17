package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.ResourceException;
import jakarta.resource.cci.Connection;
import jakarta.resource.cci.ConnectionFactory;
import jakarta.resource.cci.ConnectionSpec;
import jakarta.resource.cci.IndexedRecord;
import jakarta.resource.cci.MappedRecord;
import jakarta.resource.cci.RecordFactory;
import jakarta.resource.cci.ResourceAdapterMetaData;
import java.io.Serial;
import javax.naming.Reference;

public class CalendarConnectionFactory implements ConnectionFactory {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CalendarManagedConnectionFactory managedConnectionFactory;
    private Reference reference;

    public CalendarConnectionFactory(CalendarManagedConnectionFactory managedConnectionFactory) {
        this.managedConnectionFactory = managedConnectionFactory;
    }

    @Override
    public Connection getConnection() throws ResourceException {
        CalendarManagedConnection managedConnection =
                (CalendarManagedConnection) managedConnectionFactory.createManagedConnection(null, null);
        return (Connection) managedConnection.getConnection(null, null);
    }

    @Override
    public Connection getConnection(ConnectionSpec properties) throws ResourceException {
        return getConnection();
    }

    @Override
    public RecordFactory getRecordFactory() {
        return new RecordFactory() {
            @Override
            public <K, V> MappedRecord<K, V> createMappedRecord(String recordName) {
                return (MappedRecord<K, V>) new CalendarMappedRecord(recordName);
            }

            @Override
            public <E> IndexedRecord<E> createIndexedRecord(String recordName) throws ResourceException {
                throw new jakarta.resource.NotSupportedException("Indexed records are not supported");
            }
        };
    }

    @Override
    public ResourceAdapterMetaData getMetaData() {
        return new ResourceAdapterMetaData() {
            @Override
            public String getAdapterVersion() {
                return "1.0";
            }

            @Override
            public String getAdapterVendorName() {
                return "ITMO";
            }

            @Override
            public String getAdapterName() {
                return "Calendar JCA Adapter";
            }

            @Override
            public String getAdapterShortDescription() {
                return "Educational JCA adapter for interview calendar export";
            }

            @Override
            public String getSpecVersion() {
                return "2.1";
            }

            @Override
            public String[] getInteractionSpecsSupported() {
                return new String[] {CalendarInteractionSpec.class.getName()};
            }

            @Override
            public boolean supportsExecuteWithInputAndOutputRecord() {
                return true;
            }

            @Override
            public boolean supportsExecuteWithInputRecordOnly() {
                return true;
            }

            @Override
            public boolean supportsLocalTransactionDemarcation() {
                return false;
            }
        };
    }

    @Override
    public Reference getReference() {
        return reference;
    }

    @Override
    public void setReference(Reference reference) {
        this.reference = reference;
    }
}
