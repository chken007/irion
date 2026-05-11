package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily Buy Box status snapshot for a single SKU.
 *
 * <p>Captures whether we won the Buy Box on a given date, identifies the
 * winner, and records the winning price alongside our own price for
 * competitive analysis.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyBoxStatus {

    /** Unique SKU identifier. */
    private String skuId;

    /** Date of this Buy Box observation. */
    private LocalDate date;

    /** Whether we won the Buy Box on this date. */
    private boolean won;

    /**
     * Identifies the Buy Box winner on this date.
     * Valid values: {@code SELF}, {@code COMPETITOR}, {@code UNKNOWN}.
     */
    private String winner;

    /** The price displayed in the Buy Box (the winner's price). */
    private BigDecimal winnerPrice;

    /** Our own listed price at the time of observation. */
    private BigDecimal myPrice;
}
