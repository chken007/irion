package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Computed key performance indicators for a single SKU.
 *
 * <p>Aggregated from multiple {@link SkuData} rows over a configurable
 * time window.  Includes Weeks-of-Supply (WOS), Out-of-Stock risk,
 * Return on Ad Spend (ROAS), and Buy Box win rate.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuMetrics {

    /** Unique SKU identifier (maps to {@code product_catalog.sku_code}). */
    private String skuId;

    /** Product ID (maps to {@code product_catalog.id}). */
    private Long productId;

    /** Human-readable product name. */
    private String productName;

    /** Product category. */
    private String category;

    /** Weeks-of-Supply: {@code inventory / average weekly sales}. */
    private double wos;

    /**
     * Out-of-Stock risk index, normalised to 0–100.
     * Higher values indicate greater risk of stocking out.
     */
    private double oosRiskIndex;

    /** Return on Ad Spend: {@code revenue / adSpend}. */
    private BigDecimal roas;

    /** Buy Box win rate as a fraction (0.0–1.0). */
    private Double buyBoxWinRate;

    /**
     * Dominant Buy Box status across the observation window.
     * One of {@code WON}, {@code LOST}, {@code SHARED}, or {@code UNKNOWN}.
     */
    private String buyBoxStatus;

    /** Total number of daily observations used for aggregation. */
    private int totalObservations;
}
