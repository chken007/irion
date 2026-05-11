package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily competitor price observation for a product at a retailer.
 *
 * <p>Analytical entity stored as Parquet and queried via DuckDB.
 * Used for pricing intelligence and competitive analysis.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompetitorPrice {

    /** Product reference (maps to {@link Product#id}). */
    private Long productId;

    /** Retailer reference (maps to {@link Retailer#id}). */
    private Long retailerId;

    /** Date of the price observation. */
    private LocalDate date;

    /** Competitor name or identifier. */
    private String competitorName;

    /** Observed competitor price. */
    private BigDecimal price;
}
