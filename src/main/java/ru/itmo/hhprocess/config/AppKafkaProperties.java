package ru.itmo.hhprocess.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.kafka")
public class AppKafkaProperties {

    private Topics topics = new Topics();

    @Getter
    @Setter
    public static class Topics {
        private String applicationSubmitted;
        private String applicationScreened;
        private String interviewExportRequested;
    }
}
