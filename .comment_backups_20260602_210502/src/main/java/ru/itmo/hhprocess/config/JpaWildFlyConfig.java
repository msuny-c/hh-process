package ru.itmo.hhprocess.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * WildFly exposes its own Jakarta/JPA/Hibernate integration classes to deployed WAR files.
 * With Spring Boot 3.4 / Spring Framework 6.2 / Hibernate 6.6 the default Hibernate
 * SessionFactory proxy can conflict with Spring's EntityManagerFactoryInfo mixin because
 * both interfaces contain getSchemaManager() with different return types.
 *
 * Force Spring to proxy the plain Jakarta EntityManagerFactory interface. The application
 * still uses Hibernate under the hood, but the externally visible EntityManagerFactory
 * interface becomes container-safe for WildFly deployment.
 */
@Configuration
public class JpaWildFlyConfig {

    @Bean
    public JpaVendorAdapter jpaVendorAdapter() {
        return new HibernateJpaVendorAdapter() {
            @Override
            public Class<? extends EntityManagerFactory> getEntityManagerFactoryInterface() {
                return EntityManagerFactory.class;
            }
        };
    }
}
