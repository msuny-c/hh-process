package ru.itmo.hhprocess.config;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import org.hibernate.cfg.AvailableSettings;
import org.postgresql.xa.PGXADataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;

@Configuration
@EnableJpaRepositories(
        basePackages = "ru.itmo.hhprocess.schedule.repository",
        entityManagerFactoryRef = "scheduleEntityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class ScheduleXaDataSourceConfig {

    @Bean
    public XADataSource scheduleXaDataSource(ScheduleDbProperties properties) {
        PGXADataSource dataSource = new PGXADataSource();
        dataSource.setServerNames(new String[] {properties.getHost()});
        dataSource.setPortNumbers(new int[] {properties.getPort()});
        dataSource.setDatabaseName(properties.getDatabase());
        dataSource.setUser(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setCurrentSchema(properties.getSchema());
        return dataSource;
    }

    @Bean(name = "scheduleDataSource")
    public DataSource scheduleDataSource(
            XADataSourceWrapper xaDataSourceWrapper,
            @Qualifier("scheduleXaDataSource") XADataSource xaDataSource
    ) throws Exception {
        return xaDataSourceWrapper.wrapDataSource(xaDataSource);
    }

    @Bean(name = "scheduleEntityManagerFactory")
    @DependsOn("scheduleFlyway")
    public LocalContainerEntityManagerFactoryBean scheduleEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("scheduleDataSource") DataSource dataSource,
            JpaProperties jpaProperties,
            ScheduleDbProperties scheduleDbProperties
    ) {
        Map<String, Object> properties = new HashMap<>(jpaProperties.getProperties());
        properties.put(AvailableSettings.DEFAULT_SCHEMA, scheduleDbProperties.getSchema());
        properties.put(AvailableSettings.TRANSACTION_COORDINATOR_STRATEGY, "jta");
        properties.put("jakarta.persistence.transactionType", "JTA");

        return builder
                .dataSource(dataSource)
                .packages("ru.itmo.hhprocess.schedule.entity")
                .persistenceUnit("schedule")
                .properties(properties)
                .jta(true)
                .build();
    }
}
