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
            @Value("${app.eis.remote-base-url:}") String remoteBaseUrl,
            @Value("${app.eis.api-key:}") String apiKey,
            @Value("${app.role:api}") String appRole) {
        String url = remoteBaseUrl == null ? "" : remoteBaseUrl.trim();
        if ("api".equalsIgnoreCase(appRole.trim()) && url.isEmpty()) {
            throw new IllegalStateException(
                    "app.eis.remote-base-url (APP_EIS_REMOTE_BASE_URL) is required for APP_ROLE=api; in-memory EIS is not supported.");
        }
        return new CalendarManagedConnectionFactory(url, apiKey);
    }

    @Bean
    public CalendarConnectionFactory calendarConnectionFactory(CalendarManagedConnectionFactory managedConnectionFactory) {
        return (CalendarConnectionFactory) managedConnectionFactory.createConnectionFactory();
    }
}
