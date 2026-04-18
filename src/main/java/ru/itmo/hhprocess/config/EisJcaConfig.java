package ru.itmo.hhprocess.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.itmo.hhprocess.integration.eis.jca.CalendarConnectionFactory;
import ru.itmo.hhprocess.integration.eis.jca.CalendarManagedConnectionFactory;

@Configuration
public class EisJcaConfig {

    @Bean
    public CalendarManagedConnectionFactory calendarManagedConnectionFactory(
            @Value("${app.eis.remote-base-url:}") String remoteBaseUrl) {
        return new CalendarManagedConnectionFactory(remoteBaseUrl);
    }

    @Bean
    public CalendarConnectionFactory calendarConnectionFactory(CalendarManagedConnectionFactory managedConnectionFactory) {
        return (CalendarConnectionFactory) managedConnectionFactory.createConnectionFactory();
    }
}
