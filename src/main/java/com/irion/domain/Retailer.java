package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Retailer entity representing an e-commerce platform.
 *
 * <p>Examples: AMAZON, WALMART, TARGET.  Stored in PostgreSQL as
 * operational reference data; joined with Parquet-backed analytical
 * data in DuckDB for query-time enrichment.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Retailer {

    /** Database-generated primary key. */
    private Long id;

    /** Unique retailer code (e.g. {@code AMAZON}, {@code WALMART}). */
    private String code;

    /** Human-readable retailer name. */
    private String name;

    /** Geographic region (e.g. {@code US}, {@code EU}). */
    private String region;

    /** Whether this retailer is currently active. */
    private boolean active;
}
