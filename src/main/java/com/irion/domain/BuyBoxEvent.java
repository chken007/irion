package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily Buy Box event for a product at a retailer.
 *
 * <p>Captures whether we won the Buy Box on a given date, identifies the
 * winner type, and records the winning price alongside our own price for
 * competitive analysis.  Renamed from {@code BuyBoxStatus} to better reflect
 * that each row is a point-in-time event.</p>
 *
 * <p>Analytical entity stored as Parquet and queried via DuckDB.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyBoxEvent {

    /** Product reference (maps to {@link Product#id}). */
    private Long productId;

    /** Retailer reference (maps to {@link Retailer#id}). */
    private Long retailerId;

    /** Date of this Buy Box observation. */
    private LocalDate date;

    /** Whether we won the Buy Box on this date. */
    private boolean won;

    /**
     * Identifies the Buy Box winner on this date.
     * Valid values: {@code SELF}, {@code COMPETITOR}, {@code UNKNOWN}.
     */
    private String winnerType;

    /** Our own listed price at the time of observation. */
    private BigDecimal ourPrice;

    /** The price displayed in the Buy Box (the winner's price). */
    private BigDecimal winnerPrice;
}
