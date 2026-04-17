package ru.itmo.hhprocess.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScheduleDebugService {

    private final AtomicBoolean failOnReserve;

    public ScheduleDebugService(@Value("${app.schedule.debug.fail-on-reserve:false}") boolean initialState) {
        this.failOnReserve = new AtomicBoolean(initialState);
    }

    public boolean isFailOnReserve() {
        return failOnReserve.get();
    }

    public boolean setFailOnReserve(boolean enabled) {
        failOnReserve.set(enabled);
        return failOnReserve.get();
    }
}
