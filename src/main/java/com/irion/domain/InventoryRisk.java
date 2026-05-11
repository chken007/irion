package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inventory risk assessment for a single SKU.
 *
 * <p>Computed from Weeks-of-Supply (WOS) and Out-of-Stock risk index.
 * The {@code riskLevel} categorises the SKU as {@code CRITICAL},
 * {@code WARNING}, or {@code HEALTHY} for operational dashboards.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryRisk {

    /** Unique SKU identifier. */
    private String skuId;

    /** Weeks-of-Supply. */
    private double wos;

    /** Out-of-Stock risk index (0–100). */
    private double oosRiskIndex;

    /**
     * Risk level classification.
     * Valid values: {@code CRITICAL}, {@code WARNING}, {@code HEALTHY}.
     */
    private String riskLevel;
}
