package com.irion.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Convenience wrapper around the DuckDB {@link JdbcTemplate}.
 *
 * <p>Provides DuckDB-specific helper methods (e.g. Parquet view mounting)
 * and delegates standard query/execute operations to the underlying
 * {@link JdbcTemplate}.  All callers should inject this component rather
 * than using the {@code duckDbJdbcTemplate} bean directly.</p>
 *
 * <p>Constructor injection with {@link Qualifier} ensures the correct
 * datasource is used.</p>
 */
@Slf4j
@Component
public class DuckDbTemplate {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates a {@code DuckDbTemplate} wired to the DuckDB datasource.
     *
     * @param jdbcTemplate the {@code duckDbJdbcTemplate} bean, qualified
     *                     to avoid ambiguity with the primary PostgreSQL template
     */
    public DuckDbTemplate(@Qualifier("duckDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        log.debug("DuckDbTemplate initialised");
    }

    /**
     * Mounts a Parquet file as a DuckDB view.
     *
     * <p>Executes {@code CREATE OR REPLACE VIEW <tableName> AS SELECT * FROM read_parquet('<parquetPath>')}.
     * The view can then be queried like a regular table.</p>
     *
     * @param tableName   the logical view name to create or replace
     * @param parquetPath absolute or relative path to the Parquet file
     */
    public void mountParquet(String tableName, String parquetPath) {
        String sql = "CREATE OR REPLACE VIEW " + tableName
                + " AS SELECT * FROM read_parquet('" + parquetPath + "')";
        log.debug("Mounting Parquet view: {}", sql);
        jdbcTemplate.execute(sql);
    }

    /**
     * Executes a DuckDB query and maps the result to a list of objects.
     *
     * @param <T>    the target type
     * @param sql    the DuckDB SQL query
     * @param mapper the {@link RowMapper} used to convert each row
     * @param args   optional positional parameters for the query
     * @return a list of mapped objects; never {@code null}
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        log.debug("DuckDB query: {}", sql);
        return jdbcTemplate.query(sql, mapper, args);
    }

    /**
     * Executes a DuckDB query and returns each row as a {@code Map<String, Object>}.
     *
     * @param sql  the DuckDB SQL query
     * @param args optional positional parameters for the query
     * @return a list of row maps; never {@code null}
     */
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        log.debug("DuckDB queryForList: {}", sql);
        return jdbcTemplate.queryForList(sql, args);
    }

    /**
     * Executes a DuckDB query that returns a single value.
     *
     * @param <T>  the target type (e.g. {@code Long.class}, {@code String.class})
     * @param sql  the DuckDB SQL query
     * @param type the expected return type
     * @param args optional positional parameters for the query
     * @return the single result value, or {@code null} if no rows matched
     */
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        log.debug("DuckDB queryForObject: {}", sql);
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    /**
     * Executes a DuckDB DDL or DML statement (INSERT, UPDATE, DELETE, CREATE, etc.).
     *
     * @param sql the DuckDB SQL statement to execute
     */
    public void execute(String sql) {
        log.debug("DuckDB execute: {}", sql);
        jdbcTemplate.execute(sql);
    }

    /**
     * Executes an aggregation query and maps each result row to an object.
     *
     * <p>Semantically identical to {@link #query(String, RowMapper, Object...)}
     * but the name signals intent for aggregation-style queries (GROUP BY, window
     * functions, etc.).</p>
     *
     * @param <T>    the target type
     * @param sql    the DuckDB aggregation SQL query
     * @param mapper the {@link RowMapper} used to convert each row
     * @return a list of mapped aggregation results; never {@code null}
     */
    public <T> List<T> aggregate(String sql, RowMapper<T> mapper) {
        log.debug("DuckDB aggregate: {}", sql);
        return jdbcTemplate.query(sql, mapper);
    }
}
