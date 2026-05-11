package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Advertising performance metrics for a single SKU.
 *
 * <p>Computed by {@link com.irion.analytics.AdAnalyticsService} from the
 * {@code ad_spend} DuckDB view joined with {@code product_catalog}.  Covers
 * ACOS (Advertising Cost of Sales), Share of Voice, Cannibalization Score,
 * and Incremental ROAS.</p>
 *
 * <p>All percentage fields are in range 0–100.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdMetrics {

    /** Unique SKU identifier (maps to {@code product_catalog.sku_code}). */
    private String skuId;

    /**
     * Advertising Cost of Sales — ad spend as a percentage of attributed sales.
     * <ul>
     *   <li>&lt; 15% → healthy</li>
     *   <li>15–30% → needs attention</li>
     *   <li>&gt; 30% → needs optimisation</li>
     * </ul>
     */
    private Double acos;

    /**
     * Share of Voice — this SKU's impression share as a percentage of total
     * market impressions across all SKUs.
     */
    private Double sov;

    /**
     * Cannibalization Score — percentage of days where multiple campaign types
     * overlap for the same SKU, indicating potential self-competition.
     */
    private Double cannibalizationScore;

    /** Incremental ROAS — estimated incremental return (30% of total ROAS). */
    private Double incrementalRoas;

    /** Return on Ad Spend: {@code attributed sales / ad spend}. */
    private Double roas;

    /** Total ad impressions over the observation window. */
    private Long totalImpressions;

    /** Total ad clicks over the observation window. */
    private Long totalClicks;

    /** Total ad spend over the observation window. */
    private Double totalSpend;
}
