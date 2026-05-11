package com.irion.analytics;

import com.irion.domain.InventoryRisk;
import com.irion.domain.SkuMetrics;
import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.List;

/**
 * Computes inventory health metrics — Weeks-of-Supply (WOS) and
 * Out-of-Stock (OOS) risk — from Parquet-backed DuckDB views.
 *
 * <p>WOS is calculated as {@code latest inventory / (avg daily sales × 7)}.
 * The OOS Risk Index is derived from WOS using a piecewise linear function
 * and capped at 100.  Every product is then classified as
 * {@code CRITICAL}, {@code WARNING}, or {@code HEALTHY}.</p>
 *
 * <p>After schema refactoring, this service queries separate Parquet views:
 * {@code inventory_snapshots}, {@code sales_transactions}, and joins with
 * {@code product_catalog} for product metadata.</p>
 *
 * <p>All aggregation runs entirely inside DuckDB for performance; only the
 * final OOS risk scoring is applied in Java to keep business logic readable.</p>
 */
@Slf4j
@Service
public class InventoryService {

    private final DuckDbTemplate duckDbTemplate;

    /** View/table names — hard-coded after schema refactoring. */
    private static final String INVENTORY_VIEW = "inventory_snapshots";
    private static final String SALES_VIEW = "sales_transactions";
    private static final String PRODUCT_VIEW = "product_catalog";

    /**
     * Creates the inventory service.
     *
     * @param duckDbTemplate DuckDB JDBC template for analytical queries
     */
    public InventoryService(DuckDbTemplate duckDbTemplate) {
        this.duckDbTemplate = duckDbTemplate;
    }

    /**
     * Finds products whose Weeks-of-Supply falls below the given threshold.
     *
     * <p>WOS is computed over a 28-day rolling window of daily sales and
     * compared against the most recent inventory snapshot.  Only products with
     * WOS strictly below {@code wosThreshold} are returned, together with
     * their OOS risk index and risk level classification.</p>
     *
     * @param wosThreshold maximum WOS value to include (exclusive);
     *                     products with WOS ≥ this value are filtered out
     * @return list of at-risk products, ordered by ascending WOS (most at-risk first);
     *         empty list if no product breaches the threshold
     */
    public List<InventoryRisk> findRiskySkus(double wosThreshold) {
        String sql = """
                WITH daily_sales AS (
                    SELECT product_id, date, SUM(qty) AS daily_qty
                    FROM %s
                    WHERE CAST(date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
                    GROUP BY product_id, date
                ),
                rolling_sales AS (
                    SELECT product_id, AVG(daily_qty) / 7.0 AS avg_daily_sales
                    FROM daily_sales
                    GROUP BY product_id
                ),
                latest_inventory AS (
                    SELECT product_id, MAX(stock_on_hand) AS stock_on_hand
                    FROM %s
                    WHERE (product_id, CAST(snapshot_date AS DATE)) IN (
                        SELECT product_id, MAX(CAST(snapshot_date AS DATE))
                        FROM %s
                        GROUP BY product_id
                    )
                    GROUP BY product_id
                ),
                wos_calc AS (
                    SELECT l.product_id,
                           l.stock_on_hand,
                           COALESCE(r.avg_daily_sales, 0.001) AS avg_daily_sales,
                           CASE
                               WHEN COALESCE(r.avg_daily_sales, 0) > 0
                                   THEN l.stock_on_hand / (r.avg_daily_sales * 7.0)
                               ELSE 999
                           END AS wos
                    FROM latest_inventory l
                    LEFT JOIN rolling_sales r ON l.product_id = r.product_id
                )
                SELECT w.product_id,
                       p.sku_code        AS sku_id,
                       p.product_name,
                       p.category,
                       w.wos
                FROM wos_calc w
                LEFT JOIN %s p ON w.product_id = p.id
                WHERE w.wos < %f
                ORDER BY w.wos ASC
                """.formatted(SALES_VIEW, INVENTORY_VIEW, INVENTORY_VIEW, PRODUCT_VIEW, wosThreshold);

        log.debug("Finding risky products with WOS threshold < {}", wosThreshold);

        List<InventoryRisk> results = duckDbTemplate.aggregate(sql, (ResultSet rs, int rowNum) -> {
            String skuId = rs.getString("sku_id");
            double wos = rs.getDouble("wos");
            double oosRiskIndex = computeOosRiskIndex(wos);
            String riskLevel = classifyRisk(oosRiskIndex);
            return InventoryRisk.builder()
                    .skuId(skuId)
                    .wos(wos)
                    .oosRiskIndex(oosRiskIndex)
                    .riskLevel(riskLevel)
                    .build();
        });

        log.info("Found {} risky products below WOS threshold {}", results.size(), wosThreshold);
        return results;
    }

    /**
     * Computes Weeks-of-Supply and OOS risk for every product in the dataset.
     *
     * <p>Unlike {@link #findRiskySkus(double)} this method does not filter
     * by a threshold — it returns the complete population so dashboards can
     * show the full distribution.  Results are ordered by ascending WOS
     * (riskier products first).</p>
     *
     * @return list of {@link SkuMetrics} for all products with {@code wos} and
     *         {@code oosRiskIndex} populated; other metric fields are left
     *         at their defaults
     */
    public List<SkuMetrics> getWosForAllSkus() {
        String sql = """
                WITH daily_sales AS (
                    SELECT product_id, date, SUM(qty) AS daily_qty
                    FROM %s
                    WHERE CAST(date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
                    GROUP BY product_id, date
                ),
                rolling_sales AS (
                    SELECT product_id, AVG(daily_qty) / 7.0 AS avg_daily_sales
                    FROM daily_sales
                    GROUP BY product_id
                ),
                latest_inventory AS (
                    SELECT product_id, MAX(stock_on_hand) AS stock_on_hand
                    FROM %s
                    WHERE (product_id, CAST(snapshot_date AS DATE)) IN (
                        SELECT product_id, MAX(CAST(snapshot_date AS DATE))
                        FROM %s
                        GROUP BY product_id
                    )
                    GROUP BY product_id
                )
                SELECT l.product_id,
                       p.sku_code        AS sku_id,
                       p.product_name,
                       p.category,
                       l.stock_on_hand,
                       COALESCE(r.avg_daily_sales, 0.001) AS avg_daily_sales,
                       CASE
                           WHEN COALESCE(r.avg_daily_sales, 0) > 0
                               THEN l.stock_on_hand / (r.avg_daily_sales * 7.0)
                           ELSE 999
                       END AS wos
                FROM latest_inventory l
                LEFT JOIN rolling_sales r ON l.product_id = r.product_id
                LEFT JOIN %s p ON l.product_id = p.id
                ORDER BY wos ASC
                """.formatted(SALES_VIEW, INVENTORY_VIEW, INVENTORY_VIEW, PRODUCT_VIEW);

        log.debug("Computing WOS for all products");

        List<SkuMetrics> results = duckDbTemplate.aggregate(sql, (ResultSet rs, int rowNum) -> {
            String skuId = rs.getString("sku_id");
            long productId = rs.getLong("product_id");
            String productName = rs.getString("product_name");
            String category = rs.getString("category");
            double wos = rs.getDouble("wos");
            double oosRiskIndex = computeOosRiskIndex(wos);
            return SkuMetrics.builder()
                    .skuId(skuId)
                    .productId(productId)
                    .productName(productName)
                    .category(category)
                    .wos(wos)
                    .oosRiskIndex(oosRiskIndex)
                    .build();
        });

        log.info("Computed WOS for {} products", results.size());
        return results;
    }

    // ───────────────────────────── private helpers ─────────────────────────────

    /**
     * Derives the Out-of-Stock risk index (0–100) from Weeks-of-Supply.
     *
     * <p>Piecewise linear scoring:</p>
     * <ul>
     *   <li>WOS &lt; 1.0 → 90 + (1.0 − WOS) × 10</li>
     *   <li>WOS &lt; 2.0 → 50 + (2.0 − WOS) × 40</li>
     *   <li>WOS &lt; 4.0 → (4.0 − WOS) × 25</li>
     *   <li>WOS ≥ 4.0 → 0 (healthy)</li>
     * </ul>
     * <p>The result is capped at 100.</p>
     *
     * @param wos Weeks-of-Supply value
     * @return OOS risk index in range [0, 100]
     */
    static double computeOosRiskIndex(double wos) {
        double index;
        if (wos < 1.0) {
            index = 90.0 + (1.0 - wos) * 10.0;
        } else if (wos < 2.0) {
            index = 50.0 + (2.0 - wos) * 40.0;
        } else if (wos < 4.0) {
            index = (4.0 - wos) * 25.0;
        } else {
            index = 0.0;
        }
        return Math.min(index, 100.0);
    }

    /**
     * Classifies a product's risk level from its OOS risk index.
     *
     * @param oosRiskIndex OOS risk index in [0, 100]
     * @return {@code CRITICAL} (≥70), {@code WARNING} (≥40), or {@code HEALTHY}
     */
    static String classifyRisk(double oosRiskIndex) {
        if (oosRiskIndex >= 70.0) {
            return "CRITICAL";
        }
        if (oosRiskIndex >= 40.0) {
            return "WARNING";
        }
        return "HEALTHY";
    }
}
