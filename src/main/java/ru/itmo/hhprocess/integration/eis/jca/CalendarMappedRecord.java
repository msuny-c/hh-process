package ru.itmo.hhprocess.integration.eis.jca;

import jakarta.resource.cci.MappedRecord;
import java.util.HashMap;

public class CalendarMappedRecord extends HashMap<String, Object> implements MappedRecord<String, Object> {

    private String recordName;
    private String recordShortDescription;

    public CalendarMappedRecord(String recordName) {
        this.recordName = recordName;
    }

    @Override
    public String getRecordName() {
        return recordName;
    }

    @Override
    public void setRecordName(String recordName) {
        this.recordName = recordName;
    }

    @Override
    public void setRecordShortDescription(String recordShortDescription) {
        this.recordShortDescription = recordShortDescription;
    }

    @Override
    public String getRecordShortDescription() {
        return recordShortDescription;
    }

    @Override
    public Object clone() {
        CalendarMappedRecord clone = new CalendarMappedRecord(recordName);
        clone.setRecordShortDescription(recordShortDescription);
        clone.putAll(this);
        return clone;
    }
}
