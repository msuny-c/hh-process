package ru.itmo.hhprocess.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScheduleFlywayConfig {

    @Bean(name = "scheduleFlyway")
    public ScheduleSchemaMigrated scheduleFlyway(
            @Qualifier("scheduleDataSource") DataSource dataSource,
            ScheduleDbProperties properties
    ) {
        Flyway.configure()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .dataSource(dataSource)
                .schemas(properties.getSchema())
                .locations("classpath:db/schedule-migration")
                .load()
                .migrate();
        return new ScheduleSchemaMigrated();
    }

    public record ScheduleSchemaMigrated() {}
}
