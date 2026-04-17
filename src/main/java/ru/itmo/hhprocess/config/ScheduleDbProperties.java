package ru.itmo.hhprocess.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.schedule-db")
public class ScheduleDbProperties {

    private String host;
    private int port;
    private String database;
    private String schema;
    private String username;
    private String password;
}
