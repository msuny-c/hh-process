package ru.itmo.hhprocess.config;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import org.hibernate.cfg.AvailableSettings;
import org.postgresql.xa.PGXADataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceUnitManager;

@Configuration
@EntityScan(basePackages = "ru.itmo.hhprocess.entity")
@EnableJpaRepositories(
        basePackages = "ru.itmo.hhprocess.repository",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class MainPersistenceConfig {

    @Bean
    public EntityManagerFactoryBuilder entityManagerFactoryBuilder(
            JpaProperties jpaProperties,
            ObjectProvider<PersistenceUnitManager> persistenceUnitManager
    ) {
        JpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        return new EntityManagerFactoryBuilder(vendorAdapter, jpaProperties.getProperties(),
                persistenceUnitManager.getIfAvailable());
    }

    @Bean
    @Primary
    public XADataSource applicationXaDataSource(
            @Value("${POSTGRES_HOST}") String host,
            @Value("${POSTGRES_PORT}") int port,
            @Value("${POSTGRES_DB}") String database,
            @Value("${POSTGRES_USER}") String user,
            @Value("${POSTGRES_PASSWORD}") String password,
            @Value("${POSTGRES_SCHEMA:public}") String currentSchema
    ) {
        PGXADataSource dataSource = new PGXADataSource();
        dataSource.setServerNames(new String[] {host});
        dataSource.setPortNumbers(new int[] {port});
        dataSource.setDatabaseName(database);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setCurrentSchema(currentSchema);
        return dataSource;
    }

    @Bean(name = {"dataSource", "mainDataSource"})
    @Primary
    public DataSource mainDataSource(
            XADataSourceWrapper xaDataSourceWrapper,
            @Qualifier("applicationXaDataSource") XADataSource xaDataSource
    ) throws Exception {
        return xaDataSourceWrapper.wrapDataSource(xaDataSource);
    }

    @Bean(name = "entityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("mainDataSource") DataSource dataSource,
            JpaProperties jpaProperties,
            @Value("${POSTGRES_SCHEMA:public}") String schema
    ) {
        return builder
                .dataSource(dataSource)
                .packages("ru.itmo.hhprocess.entity")
                .persistenceUnit("main")
                .properties(commonJpaProperties(jpaProperties, schema))
                .jta(true)
                .build();
    }

    private Map<String, Object> commonJpaProperties(JpaProperties jpaProperties, String schema) {
        Map<String, Object> properties = new HashMap<>(jpaProperties.getProperties());
        properties.put(AvailableSettings.DEFAULT_SCHEMA, schema);
        properties.put(AvailableSettings.TRANSACTION_COORDINATOR_STRATEGY, "jta");
        properties.put("jakarta.persistence.transactionType", "JTA");
        return properties;
    }
}
