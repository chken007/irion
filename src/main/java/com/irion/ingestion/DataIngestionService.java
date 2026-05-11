package com.irion.ingestion;

import com.irion.domain.IngestionResult;
import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Orchestrates the end-to-end data ingestion flow from a raw source file
 * (CSV or JSON) to a queryable DuckDB view backed by a Parquet file in
 * the Silver layer.
 *
 * <h3>Ingestion pipeline</h3>
 * <ol>
 *   <li><b>Schema detection</b> — {@link SchemaDetector} samples the file
 *       and infers column names and DuckDB types.</li>
 *   <li><b>Parquet conversion</b> — {@link ParquetConverter} loads the source
 *       into a temporary DuckDB table and copies it to a Zstd-compressed
 *       Parquet file in the Silver directory.</li>
 *   <li><b>View mounting</b> — {@link DuckDbTemplate#mountParquet} registers
 *       the Parquet file as a DuckDB view so it can be queried like a table.</li>
 *   <li><b>Verification</b> — a {@code SELECT COUNT(*)} confirms the view is
 *       readable and records the ingested row count.</li>
 * </ol>
 *
 * <p>On any failure the method returns an {@link IngestionResult} with
 * {@code status = "FAILED"} and a descriptive {@code errorMessage}; it does
 * not propagate the exception to the caller.</p>
 */
@Slf4j
@Service
public class DataIngestionService {

    private final DuckDbTemplate duckDbTemplate;
    private final SchemaDetector schemaDetector;
    private final ParquetConverter parquetConverter;
    private final Path silverDir;

    /**
     * Creates the ingestion service with all required collaborators.
     *
     * @param duckDbTemplate    DuckDB JDBC template for queries and view mounting
     * @param schemaDetector    detects column names and types from source files
     * @param parquetConverter  converts CSV/JSON sources to Parquet
     * @param silverDir         output directory for Parquet files (from {@code irion.data.silver-dir})
     */
    public DataIngestionService(
            DuckDbTemplate duckDbTemplate,
            SchemaDetector schemaDetector,
            ParquetConverter parquetConverter,
            @Value("${irion.data.silver-dir}") String silverDir) {
        this.duckDbTemplate = duckDbTemplate;
        this.schemaDetector = schemaDetector;
        this.parquetConverter = parquetConverter;
        this.silverDir = Path.of(silverDir);
    }

    /**
     * Ingests a CSV or JSON source file into the Irion data platform.
     *
     * <p>On success the returned result has {@code status = "SUCCESS"} with the
     * ingested row count, Parquet file path, elapsed duration, and source file name.
     * On failure the status is {@code "FAILED"} with a descriptive error message.</p>
     *
     * @param sourcePath path to the source file to ingest
     * @return an {@link IngestionResult} capturing the outcome of the operation
     */
    public IngestionResult ingest(Path sourcePath) {
        long startMs = System.currentTimeMillis();
        String fileName = sourcePath.getFileName() != null
                ? sourcePath.getFileName().toString()
                : sourcePath.toString();

        log.info("Starting ingestion for: {}", sourcePath.toAbsolutePath());

        try {
            // 1. Detect schema → table name + column definitions
            SchemaDetector.DetectedSchema schema = schemaDetector.detectSchema(sourcePath);
            String tableName = schema.getTableName();
            log.info("Detected schema for '{}': {} columns, table name '{}'",
                    fileName, schema.getColumns().size(), tableName);

            // 2. Convert to Parquet in the Silver directory
            String parquetPath = parquetConverter.convertToParquet(sourcePath, tableName, silverDir);

            // 3. Mount the Parquet file as a DuckDB view
            duckDbTemplate.mountParquet(tableName, parquetPath);
            log.info("Mounted view '{}' ← {}", tableName, parquetPath);

            // 4. Verify by counting rows
            int rowCount = verifyRowCount(tableName);
            long durationMs = System.currentTimeMillis() - startMs;

            log.info("Ingestion complete: {} → {} rows in {} ms", fileName, rowCount, durationMs);

            return IngestionResult.builder()
                    .fileName(fileName)
                    .rowsIngested(rowCount)
                    .durationMs(durationMs)
                    .parquetPath(parquetPath)
                    .status("SUCCESS")
                    .build();

        } catch (IngestionException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Ingestion failed for '{}': {}", fileName, e.getMessage(), e);
            return IngestionResult.builder()
                    .fileName(fileName)
                    .rowsIngested(0)
                    .durationMs(durationMs)
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build();

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Unexpected ingestion error for '{}': {}", fileName, e.getMessage(), e);
            return IngestionResult.builder()
                    .fileName(fileName)
                    .rowsIngested(0)
                    .durationMs(durationMs)
                    .status("FAILED")
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Runs {@code SELECT COUNT(*) FROM <tableName>} against the freshly mounted
     * DuckDB view to confirm it is readable and to obtain the row count.
     *
     * @param tableName the view name to query
     * @return the number of rows in the view
     * @throws IngestionException if the query fails
     */
    private int verifyRowCount(String tableName) {
        try {
            Long count = duckDbTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName, Long.class);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            throw new IngestionException(
                    "Row-count verification failed for view '" + tableName + "': " + e.getMessage(), e);
        }
    }
}
