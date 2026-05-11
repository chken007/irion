package com.irion.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.DecisionLog;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence and logging service for the rule-engine action pipeline.
 *
 * <p>Writes every triggered {@link BusinessAction} to the {@code business_action}
 * table and every decision to the {@code decision_log} audit table.  Both tables
 * live in the primary PostgreSQL database (see {@code DataSourceConfig}).</p>
 *
 * <p>This service uses the {@code @Primary} {@link JdbcTemplate} — it never
 * touches the DuckDB analytical store.</p>
 */
@Slf4j
@Service
public class ActionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the action service wired to the primary (PostgreSQL) datasource.
     *
     * @param jdbcTemplate the {@code @Primary} JDBC template for PostgreSQL
     * @param objectMapper Jackson {@link ObjectMapper} for JSON serialisation
     */
    public ActionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ── Row mappers ────────────────────────────────────────────────

    private static final RowMapper<BusinessAction> ACTION_ROW_MAPPER = (rs, rowNum) ->
            BusinessAction.builder()
                    .id(rs.getLong("id"))
                    .productId(rs.getString("product_id"))
                    .ruleName(rs.getString("rule_name"))
                    .actionType(ActionType.valueOf(rs.getString("action_type")))
                    .reason(rs.getString("reason"))
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .executed(rs.getBoolean("executed"))
                    .build();

    private static final RowMapper<DecisionLog> DECISION_ROW_MAPPER = (rs, rowNum) ->
            DecisionLog.builder()
                    .id(rs.getLong("id"))
                    .productId(rs.getString("product_id"))
                    .ruleName(rs.getString("rule_name"))
                    .actionType(rs.getString("action_type"))
                    .context(rs.getString("context"))
                    .decidedAt(rs.getTimestamp("decided_at").toLocalDateTime())
                    .build();

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Persists a {@link BusinessAction} to the {@code business_action} table.
     *
     * @param action the action to persist (without an {@code id})
     * @return the same action with its database-generated {@code id} populated
     */
    public BusinessAction recordAction(BusinessAction action) {
        String sql = """
                INSERT INTO business_action (product_id, rule_name, action_type, reason, created_at, executed)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, action.getProductId());
            ps.setString(2, action.getRuleName());
            ps.setString(3, action.getActionType().name());
            ps.setString(4, action.getReason());
            ps.setTimestamp(5, toTimestamp(action.getCreatedAt()));
            ps.setBoolean(6, action.isExecuted());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            action.setId(key.longValue());
        }

        log.debug("Recorded business_action id={} productId={} rule={} action={}",
                action.getId(), action.getProductId(), action.getRuleName(), action.getActionType());
        return action;
    }

    /**
     * Writes a decision audit record to the {@code decision_log} table.
     *
     * <p>Serialises the current metrics snapshot into a JSON context map
     * that is stored in the {@code JSONB} column for post-hoc analysis.</p>
     *
     * @param action  the business action that was triggered
     * @param metrics the SKU metrics snapshot at the time of decision
     * @return a {@link DecisionLog} entity with the database-generated {@code id}
     * @throws RuntimeException wrapping {@link JsonProcessingException} if
     *         serialisation fails (should not happen with standard types)
     */
    public DecisionLog logDecision(BusinessAction action, SkuMetrics metrics) {
        String contextJson = buildContextJson(metrics);

        String sql = """
                INSERT INTO decision_log (product_id, rule_name, action_type, context, decided_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, action.getProductId());
            ps.setString(2, action.getRuleName());
            ps.setString(3, action.getActionType().name());
            ps.setString(4, contextJson);
            ps.setTimestamp(5, toTimestamp(action.getCreatedAt()));
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey() != null ? keyHolder.getKey().longValue() : 0L;

        DecisionLog decisionLog = DecisionLog.builder()
                .id(id)
                .productId(action.getProductId())
                .ruleName(action.getRuleName())
                .actionType(action.getActionType().name())
                .context(contextJson)
                .decidedAt(action.getCreatedAt())
                .build();

        log.debug("Recorded decision_log id={} productId={} rule={}", id, decisionLog.getProductId(), decisionLog.getRuleName());
        return decisionLog;
    }

    /**
     * Persists a batch of triggered actions and logs each decision.
     *
     * <p>In V1, actions are marked {@code executed = true} after recording
     * (simulated execution — no Amazon API call is made yet).  Each action
     * is individually committed; a failure in one does not roll back
     * previously-persisted siblings.</p>
     *
     * @param actions the list of triggered {@link BusinessAction}s
     * @param metrics the {@link SkuMetrics} for the SKU being evaluated
     *                (used to populate the decision context)
     */
    public void executeActions(List<BusinessAction> actions, SkuMetrics metrics) {
        for (BusinessAction action : actions) {
            try {
                BusinessAction recorded = recordAction(action);

                log.info("ACTION_RECOMMENDED productId={} rule={} action={} reason={}",
                        recorded.getProductId(), recorded.getRuleName(),
                        recorded.getActionType(), recorded.getReason());

                // V1: mark as simulated execution
                markExecuted(recorded.getId());

                // Write audit trail
                logDecision(action, metrics);
            } catch (Exception e) {
                log.error("Failed to execute action productId={} rule={} action={}: {}",
                        action.getProductId(), action.getRuleName(),
                        action.getActionType(), e.getMessage(), e);
            }
        }
    }

    /**
     * Retrieves the action history for a given product.
     *
     * @param productId the product identifier
     * @return list of {@link BusinessAction}s ordered by creation time descending;
     *         never {@code null}
     */
    public List<BusinessAction> getActionHistory(String productId) {
        String sql = "SELECT * FROM business_action WHERE product_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ACTION_ROW_MAPPER, productId);
    }

    /**
     * Retrieves the decision audit trail for a given product.
     *
     * @param productId the product identifier
     * @return list of {@link DecisionLog}s ordered by decision time descending;
     *         never {@code null}
     */
    public List<DecisionLog> getDecisionHistory(String productId) {
        String sql = "SELECT * FROM decision_log WHERE product_id = ? ORDER BY decided_at DESC";
        return jdbcTemplate.query(sql, DECISION_ROW_MAPPER, productId);
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Marks an action as executed in the database (V1 simulated execution).
     */
    private void markExecuted(Long actionId) {
        jdbcTemplate.update("UPDATE business_action SET executed = TRUE WHERE id = ?", actionId);
    }

    /**
     * Builds a JSON context map from the current SKU metrics snapshot.
     */
    private String buildContextJson(SkuMetrics metrics) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("wos", metrics.getWos());
        context.put("oosRisk", metrics.getOosRiskIndex());
        context.put("roas", metrics.getRoas());
        context.put("buyBoxStatus", metrics.getBuyBoxStatus());
        context.put("buyBoxWinRate", metrics.getBuyBoxWinRate());

        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise decision context to JSON", e);
        }
    }

    /**
     * Converts {@link LocalDateTime} to {@link Timestamp}, handling {@code null}.
     */
    private static Timestamp toTimestamp(LocalDateTime dateTime) {
        return dateTime != null ? Timestamp.valueOf(dateTime) : null;
    }
}
