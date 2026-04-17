package ru.itmo.hhprocess.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AppKafkaProperties.class,
        ScheduleDbProperties.class
})
public class AppConfig {
}
