package com.irion.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the schema of CSV and JSON files by sampling the first 100 rows
 * and inferring the narrowest DuckDB type for each column.
 *
 * <h3>Supported formats</h3>
 * <ul>
 *   <li><b>CSV</b> — comma-delimited with a header row. Values may be quoted.</li>
 *   <li><b>JSON</b> — either a JSON array of objects or newline-delimited JSON (NDJSON).</li>
 * </ul>
 *
 * <h3>Type inference rules (narrowest wins)</h3>
 * <ol>
 *   <li>If every non-empty value parses as {@link Long} → {@code BIGINT}</li>
 *   <li>Else if every non-empty value parses as {@link Double} → {@code DOUBLE}</li>
 *   <li>Else if every non-empty value parses as {@link LocalDate} ({@code yyyy-MM-dd}) → {@code DATE}</li>
 *   <li>Otherwise → {@code VARCHAR}</li>
 * </ol>
 *
 * <p>Empty / blank cells are skipped during inference.</p>
 */
@Slf4j
@Component
public class SchemaDetector {

    private static final int SAMPLE_ROWS = 100;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Detects the schema of the given file.
     *
     * @param filePath path to a CSV or JSON file
     * @return a {@link DetectedSchema} containing the inferred column definitions and table name
     * @throws IngestionException if the file cannot be read or the format is unrecognised
     */
    public DetectedSchema detectSchema(Path filePath) {
        if (!Files.exists(filePath)) {
            throw new IngestionException("File not found: " + filePath.toAbsolutePath());
        }

        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".json")) {
            return detectJsonSchema(filePath);
        }
        return detectCsvSchema(filePath);
    }

    // ── CSV detection ──────────────────────────────────────────────────────

    /**
     * Reads a CSV file: first line = header (column names), subsequent lines = data rows.
     * Samples up to {@link #SAMPLE_ROWS} data rows for type inference.
     */
    private DetectedSchema detectCsvSchema(Path filePath) {
        log.debug("Detecting CSV schema for: {}", filePath);

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new IngestionException("CSV file is empty or has no header: " + filePath);
            }

            String[] columnNames = splitCsvLine(headerLine);
            String tableName = toTableName(filePath.getFileName().toString());

            // Collect sample values per column
            Map<String, List<String>> sampleValues = new LinkedHashMap<>();
            for (String col : columnNames) {
                sampleValues.put(col, new ArrayList<>());
            }

            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null && rowCount < SAMPLE_ROWS) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = splitCsvLine(line);
                for (int i = 0; i < columnNames.length && i < values.length; i++) {
                    String trimmed = values[i].trim();
                    if (!trimmed.isEmpty()) {
                        sampleValues.get(columnNames[i]).add(trimmed);
                    }
                }
                rowCount++;
            }

            log.debug("Sampled {} rows from CSV: {}", rowCount, filePath);
            List<ColumnDef> columns = inferColumnTypes(sampleValues);
            return DetectedSchema.builder()
                    .tableName(tableName)
                    .columns(columns)
                    .build();

        } catch (IOException e) {
            throw new IngestionException("Failed to read CSV file: " + filePath, e);
        }
    }

    // ── JSON detection ─────────────────────────────────────────────────────

    /**
     * Reads a JSON file. Attempts NDJSON (one object per line) first;
     * falls back to a top-level JSON array.
     */
    private DetectedSchema detectJsonSchema(Path filePath) {
        log.debug("Detecting JSON schema for: {}", filePath);

        try {
            String raw = Files.readString(filePath).trim();
            if (raw.startsWith("[")) {
                return detectJsonArraySchema(filePath, raw);
            }
            return detectNdjsonSchema(filePath);
        } catch (IOException e) {
            throw new IngestionException("Failed to read JSON file: " + filePath, e);
        }
    }

    private DetectedSchema detectJsonArraySchema(Path filePath, String raw) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            if (!root.isArray() || root.isEmpty()) {
                throw new IngestionException("JSON file has an empty or non-array root: " + filePath);
            }

            String tableName = toTableName(filePath.getFileName().toString());
            Map<String, List<String>> sampleValues = new LinkedHashMap<>();

            int limit = Math.min(root.size(), SAMPLE_ROWS);
            for (int i = 0; i < limit; i++) {
                JsonNode obj = root.get(i);
                if (obj.isObject()) {
                    collectJsonFields(obj, sampleValues);
                }
            }

            List<ColumnDef> columns = inferColumnTypes(sampleValues);
            return DetectedSchema.builder()
                    .tableName(tableName)
                    .columns(columns)
                    .build();

        } catch (IOException e) {
            throw new IngestionException("Failed to parse JSON array: " + filePath, e);
        }
    }

    private DetectedSchema detectNdjsonSchema(Path filePath) {
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String tableName = toTableName(filePath.getFileName().toString());
            Map<String, List<String>> sampleValues = new LinkedHashMap<>();

            String line;
            int rowCount = 0;
            while ((line = reader.readLine()) != null && rowCount < SAMPLE_ROWS) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode obj = OBJECT_MAPPER.readTree(line);
                if (obj.isObject()) {
                    collectJsonFields(obj, sampleValues);
                }
                rowCount++;
            }

            if (sampleValues.isEmpty()) {
                throw new IngestionException("No JSON objects found in NDJSON file: " + filePath);
            }

            List<ColumnDef> columns = inferColumnTypes(sampleValues);
            return DetectedSchema.builder()
                    .tableName(tableName)
                    .columns(columns)
                    .build();

        } catch (IOException e) {
            throw new IngestionException("Failed to parse NDJSON file: " + filePath, e);
        }
    }

    private void collectJsonFields(JsonNode obj, Map<String, List<String>> sampleValues) {
        obj.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode value = entry.getValue();
            if (!value.isNull()) {
                String textValue = value.isTextual() ? value.asText() : value.toString();
                sampleValues.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(textValue);
            }
        });
    }

    // ── Type inference ─────────────────────────────────────────────────────

    /**
     * For each column, inspects all sampled non-empty values and picks the
     * narrowest DuckDB type that accommodates every value.
     */
    private List<ColumnDef> inferColumnTypes(Map<String, List<String>> sampleValues) {
        List<ColumnDef> columns = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sampleValues.entrySet()) {
            String columnName = entry.getKey();
            List<String> values = entry.getValue();
            String duckDbType = inferSingleColumnType(values);
            columns.add(ColumnDef.builder()
                    .name(columnName)
                    .duckDbType(duckDbType)
                    .build());
            log.debug("Column '{}' inferred as {}", columnName, duckDbType);
        }
        return columns;
    }

    /**
     * Applies the type-inference cascade to a single column's values.
     */
    private String inferSingleColumnType(List<String> values) {
        if (values.isEmpty()) {
            return "VARCHAR";
        }

        if (values.stream().allMatch(this::isLong)) {
            return "BIGINT";
        }
        if (values.stream().allMatch(this::isDouble)) {
            return "DOUBLE";
        }
        if (values.stream().allMatch(this::isDate)) {
            return "DATE";
        }
        return "VARCHAR";
    }

    private boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDouble(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDate(String value) {
        try {
            LocalDate.parse(value, DATE_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Derives a SQL-safe table name from a file name by stripping the extension
     * and replacing any character that is not alphanumeric or underscore with an underscore.
     *
     * @param fileName the original file name (e.g. {@code sku_data_2026-05-01.csv})
     * @return a safe table name (e.g. {@code sku_data_2026_05_01})
     */
    static String toTableName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return baseName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Splits a CSV line by comma, respecting double-quoted fields.
     * This is a simplified parser suitable for header and value extraction;
     * DuckDB's {@code read_csv_auto} handles full ingestion.
     */
    private String[] splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }

    // ── Inner types ────────────────────────────────────────────────────────

    /**
     * Describes the detected schema of a source file.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedSchema {
        /** Ordered list of column definitions. */
        private List<ColumnDef> columns;
        /** SQL-safe table name derived from the file name. */
        private String tableName;
    }

    /**
     * A single column: its name and the inferred DuckDB type.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnDef {
        /** Original column name from the source file. */
        private String name;
        /** DuckDB type: {@code BIGINT}, {@code DOUBLE}, {@code DATE}, or {@code VARCHAR}. */
        private String duckDbType;
    }
}
