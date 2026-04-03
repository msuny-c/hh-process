package ru.itmo.hhprocess.config;

import javax.sql.DataSource;
import javax.sql.XADataSource;

import org.postgresql.xa.PGXADataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class XaDataSourceConfig {

    @Bean
    public XADataSource applicationXaDataSource(
            @Value("${POSTGRES_HOST}") String host,
            @Value("${POSTGRES_PORT}") int port,
            @Value("${POSTGRES_DB}") String database,
            @Value("${POSTGRES_USER}") String user,
            @Value("${POSTGRES_PASSWORD}") String password,
            @Value("${POSTGRES_SCHEMA:public}") String currentSchema
    ) {
        PGXADataSource dataSource = new PGXADataSource();
        dataSource.setServerName(host);
        dataSource.setPortNumber(port);
        dataSource.setDatabaseName(database);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setCurrentSchema(currentSchema);
        return dataSource;
    }

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(XADataSource applicationXaDataSource,
                                 XADataSourceWrapper xaDataSourceWrapper) throws Exception {
        return xaDataSourceWrapper.wrapDataSource(applicationXaDataSource);
    }
}
