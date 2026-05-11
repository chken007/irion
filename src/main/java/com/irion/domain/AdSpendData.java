package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily advertising spend and performance metrics for a product.
 *
 * <p>Analytical entity stored as Parquet and queried via DuckDB.
 * Used for Return on Ad Spend (ROAS) calculations.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdSpendData {

    /** Product reference (maps to {@link Product#id}). */
    private Long productId;

    /** Retailer reference (maps to {@link Retailer#id}). */
    private Long retailerId;

    /** Date of the ad spend record. */
    private LocalDate date;

    /** Campaign type (e.g. {@code SPONSORED_PRODUCTS}, {@code SPONSORED_BRANDS}). */
    private String campaignType;

    /** Total ad spend for this product on this date. */
    private BigDecimal spend;

    /** Number of ad impressions. */
    private long impressions;

    /** Number of ad clicks. */
    private long clicks;

    /** Attributed sales revenue from this ad spend. */
    private BigDecimal attributedSales;
}
