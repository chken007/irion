package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable audit record capturing every decision made by the rules engine.
 *
 * <p>Each row records which SKU was evaluated, which rule fired, what action
 * was taken, and the full evaluation context (stored as JSON for flexible
 * post-hoc analysis).  Persisted to the PostgreSQL {@code decision_log} table.</p>
 *
 * <p>This is the Gold-layer audit trail — append-only, never updated.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionLog {

    /** Database-generated primary key. */
    private Long id;

    /** Product that was evaluated (SKU code). */
    private String productId;

    /** Name of the rule that produced this decision. */
    private String ruleName;

    /** The action type that was decided (e.g. {@code PAUSE_AD}). */
    private String actionType;

    /**
     * Full evaluation context serialised as a JSON string.
     * Includes the metrics snapshot and rule parameters used.
     */
    private String context;

    /** Timestamp when the decision was recorded. */
    private LocalDateTime decidedAt;
}
