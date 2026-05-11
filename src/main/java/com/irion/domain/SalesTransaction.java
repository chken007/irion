package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single order-line sales transaction.
 *
 * <p>Analytical entity stored as Parquet and queried via DuckDB.
 * Joined with {@link Product} for product-level roll-ups.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesTransaction {

    /** Unique order identifier. */
    private String orderId;

    /** Product reference (maps to {@link Product#id}). */
    private Long productId;

    /** Retailer reference (maps to {@link Retailer#id}). */
    private Long retailerId;

    /** Date of the transaction. */
    private LocalDate date;

    /** Order status (e.g. {@code SHIPPED}, {@code CANCELLED}). */
    private String status;

    /** Fulfillment channel (e.g. {@code FBA}, {@code FBM}). */
    private String fulfillmentChannel;

    /** Quantity sold. */
    private int qty;

    /** Unit price. */
    private BigDecimal unitPrice;

    /** Gross revenue for this line. */
    private BigDecimal revenue;

    /** Currency code (e.g. {@code USD}). */
    private String currency;
}
