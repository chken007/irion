package com.irion.ingestion;

import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts CSV and JSON source files into compressed Parquet files
 * using DuckDB's native {@code COPY} command.
 *
 * <p>DuckDB reads the source with format-specific auto-detection
 * ({@code read_csv_auto} or {@code read_json_auto}), creates a temporary
 * table, copies it to a Zstandard-compressed Parquet file, and drops
 * the temporary table.</p>
 *
 * <p>Example conversion SQL:</p>
 * <pre>{@code
 * CREATE TABLE temp_sku_data AS SELECT * FROM read_csv_auto('/data/sku.csv', header=true);
 * COPY temp_sku_data TO '/data/silver/sku_data.parquet' (FORMAT PARQUET, COMPRESSION ZSTD);
 * DROP TABLE temp_sku_data;
 * }</pre>
 */
@Slf4j
@Component
public class ParquetConverter {

    private final DuckDbTemplate duckDbTemplate;

    /**
     * Creates a {@code ParquetConverter} wired to the DuckDB template.
     *
     * @param duckDbTemplate the DuckDB JDBC template wrapper
     */
    public ParquetConverter(DuckDbTemplate duckDbTemplate) {
        this.duckDbTemplate = duckDbTemplate;
    }

    /**
     * Converts a CSV or JSON source file to a Parquet file in the given output directory.
     *
     * <p>The output Parquet file is named {@code <tableName>.parquet} and written
     * into {@code outputDir}. If the output directory does not exist it is created
     * (including any missing parent directories).</p>
     *
     * @param sourcePath path to the source CSV or JSON file
     * @param tableName  SQL-safe table name (used for the temporary table and output file name)
     * @param outputDir  directory where the Parquet file will be written
     * @return the absolute path to the generated Parquet file
     * @throws IngestionException if the source file is missing, DuckDB execution fails,
     *                            or the output directory cannot be created
     */
    public String convertToParquet(Path sourcePath, String tableName, Path outputDir) {
        if (!Files.exists(sourcePath)) {
            throw new IngestionException("Source file not found: " + sourcePath.toAbsolutePath());
        }

        ensureDirectory(outputDir);

        String tempTable = "temp_" + tableName;
        String parquetFileName = tableName + ".parquet";
        Path parquetPath = outputDir.resolve(parquetFileName);
        String absoluteParquetPath = parquetPath.toAbsolutePath().toString();
        String absoluteSourcePath = sourcePath.toAbsolutePath().toString();

        String readFunc = getReadFunction(sourcePath, absoluteSourcePath);

        log.info("Converting {} → {}", sourcePath.getFileName(), parquetPath);

        try {
            // Step 1: load source into temporary DuckDB table
            String createSql = "CREATE TABLE " + tempTable + " AS SELECT * FROM " + readFunc;
            log.debug("Executing: {}", createSql);
            duckDbTemplate.execute(createSql);

            // Step 2: copy to compressed Parquet
            String copySql = "COPY " + tempTable + " TO '" + absoluteParquetPath
                    + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            log.debug("Executing: {}", copySql);
            duckDbTemplate.execute(copySql);

            // Step 3: drop temporary table
            String dropSql = "DROP TABLE " + tempTable;
            log.debug("Executing: {}", dropSql);
            duckDbTemplate.execute(dropSql);

        } catch (Exception e) {
            // Attempt cleanup of temporary table on failure
            try {
                duckDbTemplate.execute("DROP TABLE IF EXISTS " + tempTable);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            throw new IngestionException(
                    "Failed to convert " + sourcePath.getFileName() + " to Parquet: " + e.getMessage(), e);
        }

        log.info("Parquet file created: {}", absoluteParquetPath);
        return absoluteParquetPath;
    }

    /**
     * Selects the appropriate DuckDB read function based on the file extension.
     *
     * @param sourcePath         the source file path (used to determine the format)
     * @param absoluteSourcePath the absolute path string for embedding in SQL
     * @return a DuckDB function call like {@code read_csv_auto('...', header=true)}
     */
    private String getReadFunction(Path sourcePath, String absoluteSourcePath) {
        String fileName = sourcePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".json")) {
            return "read_json_auto('" + absoluteSourcePath + "')";
        }
        // Default to CSV with header auto-detection
        return "read_csv_auto('" + absoluteSourcePath + "', header=true)";
    }

    /**
     * Creates the output directory and all its parents if they do not exist.
     *
     * @param dir the directory path to ensure
     * @throws IngestionException if directory creation fails
     */
    private void ensureDirectory(Path dir) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.debug("Created output directory: {}", dir);
            } catch (IOException e) {
                throw new IngestionException("Cannot create output directory: " + dir.toAbsolutePath(), e);
            }
        }
    }
}
