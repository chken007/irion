package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a single file ingestion operation.
 *
 * <p>Captures metadata about a CSV file that was ingested into the
 * Bronze layer: how many rows were processed, how long it took,
 * the path to the generated Parquet file, and the outcome status.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionResult {

    /** Original source file name (e.g. {@code sku_data_2026-05-01.csv}). */
    private String fileName;

    /** Number of rows successfully ingested. */
    private int rowsIngested;

    /** Elapsed time for the ingestion in milliseconds. */
    private long durationMs;

    /** File-system path to the generated Parquet file. */
    private String parquetPath;

    /**
     * Outcome status of the ingestion.
     * Valid values: {@code SUCCESS}, {@code PARTIAL}, {@code FAILED}.
     */
    private String status;

    /**
     * Error message if the ingestion failed or was partial.
     * {@code null} when {@code status} is {@code SUCCESS}.
     */
    private String errorMessage;
}
