package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Daily inventory snapshot for a product at a retailer.
 *
 * <p>Analytical entity stored as Parquet and queried via DuckDB.
 * Used for Weeks-of-Supply (WOS) and Out-of-Stock risk calculations.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySnapshot {

    /** Product reference (maps to {@link Product#id}). */
    private Long productId;

    /** Retailer reference (maps to {@link Retailer#id}). */
    private Long retailerId;

    /** Date of the inventory snapshot. */
    private LocalDate snapshotDate;

    /** Units currently on hand. */
    private int stockOnHand;
}
