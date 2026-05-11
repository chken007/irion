package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Raw SKU data row ingested from source CSVs.
 *
 * <p>Represents a single day's snapshot of a SKU's operational and
 * financial metrics — inventory levels, sales, revenue, ad spend,
 * buy-box status, and pricing.  This is the Bronze-layer entity
 * before any enrichment or aggregation.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuData {

    /** Unique SKU identifier (e.g. {@code SKU-00001}). */
    private String skuId;

    /** Amazon Standard Identification Number. */
    private String asin;

    /** Human-readable product name. */
    private String productName;

    /** Product category (e.g. {@code Electronics}, {@code Grocery}). */
    private String category;

    /** Snapshot date for this data row. */
    private LocalDate date;

    /** Units of inventory currently on hand. */
    private int inventoryOnHand;

    /** Number of units sold during the period. */
    private BigDecimal salesUnits;

    /** Gross revenue generated during the period. */
    private BigDecimal revenue;

    /** Advertising spend for this SKU during the period. */
    private BigDecimal adSpend;

    /** Whether this SKU held the Buy Box on this date. */
    private boolean hasBuyBox;

    /** Our own listed price. */
    private BigDecimal myPrice;

    /** The competitor's listed price (if observable). */
    private BigDecimal competitorPrice;
}
