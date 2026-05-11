package com.irion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mounts Parquet files from the silver directory as DuckDB views on startup.
 */
@Slf4j
@Configuration
public class ParquetViewInitializer {

    @Bean
    CommandLineRunner mountParquetViews(
            @Qualifier("duckDbJdbcTemplate") JdbcTemplate duckDb,
            @Value("${irion.data.silver-dir:./data/silver}") String silverDir) {
        return args -> {
            Path dir = Path.of(silverDir);
            if (!Files.isDirectory(dir)) {
                log.info("Silver dir {} not found — skipping", silverDir);
                return;
            }

            Map<String, String> prefixToView = Map.of(
                "sales_transactions_", "sales_transactions",
                "inventory_snapshots_", "inventory_snapshots",
                "ad_spend_", "ad_spend",
                "buybox_events_", "buy_box_events",
                "competitor_prices_", "competitor_prices"
            );

            for (var entry : prefixToView.entrySet()) {
                String prefix = entry.getKey();
                String viewName = entry.getValue();
                List<Path> files = listFiles(dir, prefix);
                if (files.isEmpty()) {
                    log.info("No files for '{}' — skipping", viewName);
                    continue;
                }
                String fileList = files.stream()
                    .map(p -> "'" + p.toAbsolutePath().toString() + "'")
                    .collect(Collectors.joining(", "));
                try {
                    duckDb.execute("CREATE OR REPLACE VIEW " + viewName
                        + " AS SELECT * FROM read_parquet([" + fileList + "])");
                    log.info("Mounted {} ← {} files", viewName, files.size());
                } catch (Exception e) {
                    log.error("Failed to mount {}: {}", viewName, e.getMessage());
                }
            }

            // Product catalog
            Path seed = dir.resolve("product_catalog_seed.parquet");
            if (Files.exists(seed)) {
                try {
                    duckDb.execute("CREATE OR REPLACE VIEW product_catalog AS SELECT * FROM read_parquet('"
                        + seed.toAbsolutePath().toString() + "')");
                    log.info("Mounted product_catalog ← seed file");
                } catch (Exception e) {
                    log.warn("product_catalog mount failed: {}", e.getMessage());
                }
            } else {
                duckDb.execute("CREATE TABLE IF NOT EXISTS product_catalog (id BIGINT, sku_code VARCHAR, product_name VARCHAR, category VARCHAR)");
                log.info("Created empty product_catalog table");
            }
        };
    }

    private List<Path> listFiles(Path dir, String prefix) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, prefix + "*.parquet")) {
            for (Path p : s) result.add(p);
        } catch (IOException ignored) {}
        result.sort(null);
        return result;
    }
}
