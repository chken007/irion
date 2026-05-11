package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A recommended or executed business action triggered by a rule.
 *
 * <p>Each action is linked to a specific SKU and rule.  It records the
 * type of action (pause ad, resume ad, price alert, reorder alert, or
 * no action), a human-readable reason, and whether the action has been
 * automatically executed.</p>
 *
 * <p>Persisted to the PostgreSQL {@code business_action} table.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessAction {

    /**
     * Enumeration of possible business-action types.
     */
    public enum ActionType {
        /** Pause advertising campaigns for this SKU. */
        PAUSE_AD,
        /** Resume previously paused advertising campaigns. */
        RESUME_AD,
        /** Alert that pricing may need adjustment. */
        PRICE_ALERT,
        /** Alert that inventory reorder is needed. */
        REORDER_ALERT,
        /** No action required (healthy). */
        NO_ACTION,
        /** Consolidate overlapping ad campaigns to reduce cannibalization. */
        CONSOLIDATE_CAMPAIGNS,
        /** Increase bid to improve Share of Voice. */
        INCREASE_BID,
        /** Decrease bid to improve ACOS efficiency. */
        DECREASE_BID
    }

    /** Database-generated primary key. */
    private Long id;

    /** Product this action applies to (references SKU code). */
    private String productId;

    /** Name of the rule that triggered this action. */
    private String ruleName;

    /** The type of action recommended. */
    private ActionType actionType;

    /** Human-readable explanation for the action. */
    private String reason;

    /** Timestamp when the action was created. */
    private LocalDateTime createdAt;

    /** Whether the action has been automatically executed by the engine. */
    private boolean executed;
}
