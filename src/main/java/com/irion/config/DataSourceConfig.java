package com.irion.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Primary PostgreSQL data source configuration for the decision/action log.
 * <p>
 * Binds to {@code spring.datasource.postgresql} properties.  PostgreSQL
 * runs in Docker (see {@code docker/docker-compose.yml}) so the application
 * itself stays stateless.
 * </p>
 * <p>
 * This datasource is marked {@link Primary @Primary} so that Spring Boot
 * resolves it as the default for Spring Data JDBC repositories.
 * </p>
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "spring.datasource.postgresql")
public class DataSourceConfig {

    /** JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/irion} */
    private String url;

    /** JDBC driver class name (defaults to PostgreSQL) */
    private String driverClassName = "org.postgresql.Driver";

    /** Database username */
    private String username;

    /** Database password */
    private String password;

    /**
     * Creates the primary (PostgreSQL) {@link DataSource} bean.
     *
     * @return a fully configured PostgreSQL {@link DataSource}
     */
    @Primary
    @Bean(name = "dataSource")
    public DataSource postgresDataSource() {
        log.info("Creating primary PostgreSQL DataSource with URL: {}", url);
        DataSourceBuilder<?> builder = DataSourceBuilder.create()
                .url(url)
                .driverClassName(driverClassName);
        if (username != null && !username.isBlank()) {
            builder.username(username);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return builder.build();
    }

    /**
     * Creates the primary {@link JdbcTemplate} backed by the PostgreSQL {@link DataSource}.
     *
     * @param dataSource the primary (PostgreSQL) datasource
     * @return a default {@link JdbcTemplate} bean
     */
    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        log.info("Creating primary (PostgreSQL) JdbcTemplate");
        return new JdbcTemplate(dataSource);
    }
}
