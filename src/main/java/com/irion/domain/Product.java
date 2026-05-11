package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Product entity replacing the product-related fields from the old {@code SkuData}.
 *
 * <p>Stored in PostgreSQL ({@code product_catalog} table), referenced by
 * analytical Parquet views via {@code product_id} foreign key.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    /** Database-generated primary key. */
    private Long id;

    /** Owning retailer ID (references {@link Retailer#id}). */
    private Long retailerId;

    /** Internal SKU code (e.g. {@code SKU-00001}). */
    private String skuCode;

    /** Amazon Standard Identification Number or UPC. */
    private String asinUpc;

    /** Human-readable product name. */
    private String productName;

    /** Product category (e.g. {@code Electronics}, {@code Grocery}). */
    private String category;

    /** Sub-category for finer-grained grouping. */
    private String subCategory;

    /** Brand name. */
    private String brand;

    /** Product size (e.g. {@code 500ml}). */
    private String size;

    /** Product color/variant. */
    private String color;
}
