package com.irion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;

/**
 * DuckDB embedded OLAP data source — single-connection, no pool.
 * <p>
 * DuckDB is an in-process database (like SQLite). Connection pooling
 * with HikariCP causes issues because DuckDB uses thread-local state.
 * A single shared connection works correctly for embedded mode.
 */
@Slf4j
@Configuration
public class DuckDbConfig {

    @Value("${spring.datasource.duckdb.url:jdbc:duckdb:./data/irion.duckdb}")
    private String url;

    @Bean(name = "duckDbDataSource", destroyMethod = "destroy")
    public DataSource duckDbDataSource() {
        log.info("Creating DuckDB single-connection DataSource: {}", url);
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl(url);
        ds.setDriverClassName("org.duckdb.DuckDBDriver");
        ds.setSuppressClose(true); // Keep connection open, don't close after each operation
        return ds;
    }

    @Bean(name = "duckDbJdbcTemplate")
    public JdbcTemplate duckDbJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("duckDbDataSource") DataSource dataSource) {
        log.info("Creating DuckDB JdbcTemplate");
        return new JdbcTemplate(dataSource);
    }
}
