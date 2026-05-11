package com.irion.analytics;

import com.irion.domain.AdMetrics;
import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes advertising KPIs: ACOS, Share of Voice, Cannibalization Score,
 * and Incremental ROAS.
 *
 * <p>All queries run against DuckDB Parquet-backed views ({@code ad_spend}
 * and {@code product_catalog}) for analytical performance.</p>
 */
@Slf4j
@Service
public class AdAnalyticsService {

    private final DuckDbTemplate duckDbTemplate;

    public AdAnalyticsService(DuckDbTemplate duckDbTemplate) {
        this.duckDbTemplate = duckDbTemplate;
    }

    /**
     * Computes full ad metrics (ACOS, SOV, Cannibalization, Incrementality)
     * for every SKU that has ad spend data.
     *
     * @return list of {@link AdMetrics}, one per SKU with ad activity
     */
    public List<AdMetrics> computeAllAdMetrics() {
        List<AdMetrics> results = new ArrayList<>();

        // 1. ACOS and base metrics (30-day window)
        List<AdMetrics> acosMetrics = computeAcos();
        Map<String, AdMetrics> metricsBySku = acosMetrics.stream()
                .collect(Collectors.toMap(AdMetrics::getSkuId, m -> m, (a, b) -> a, LinkedHashMap::new));

        // 2. SOV (7-day window)
        computeSov(metricsBySku);

        // 3. Cannibalization (7-day window)
        computeCannibalization(metricsBySku);

        // 4. Incrementality (derived from ROAS)
        for (AdMetrics m : metricsBySku.values()) {
            if (m.getRoas() != null && m.getRoas() > 0) {
                m.setIncrementalRoas(m.getRoas() * 0.3);
            }
        }

        results.addAll(metricsBySku.values());
        log.info("Computed full ad metrics for {} SKU(s)", results.size());
        return results;
    }

    /**
     * Computes ACOS and base advertising metrics over a 30-day window.
     */
    List<AdMetrics> computeAcos() {
        String sql = """
                SELECT p.sku_code AS sku_id,
                       SUM(a.spend)            AS total_spend,
                       SUM(a.attributed_sales) AS total_sales,
                       SUM(a.impressions)      AS total_impressions,
                       SUM(a.clicks)           AS total_clicks,
                       CASE
                           WHEN SUM(a.spend) > 0
                               THEN SUM(a.attributed_sales) / SUM(a.spend)
                           ELSE NULL
                       END AS roas,
                       CASE
                           WHEN SUM(a.attributed_sales) > 0
                               THEN (SUM(a.spend) / SUM(a.attributed_sales)) * 100.0
                           ELSE NULL
                       END AS acos
                FROM ad_spend a
                JOIN product_catalog p ON a.product_id = p.id
                WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '30 days'
                GROUP BY p.id, p.sku_code
                ORDER BY p.sku_code
                """;

        return duckDbTemplate.aggregate(sql, (rs, rowNum) -> {
            Double roas = readNullableDouble(rs, "roas");
            Double acos = readNullableDouble(rs, "acos");
            return AdMetrics.builder()
                    .skuId(rs.getString("sku_id"))
                    .acos(acos)
                    .roas(roas)
                    .totalSpend(readNullableDouble(rs, "total_spend"))
                    .totalImpressions(rs.getLong("total_impressions"))
                    .totalClicks(rs.getLong("total_clicks"))
                    .build();
        });
    }

    /**
     * Computes Share of Voice (SOV) as each SKU's impression share
     * relative to the maximum-impression SKU, over a 7-day window.
     *
     * <p>Populates the {@code sov} field on each {@link AdMetrics} in the map.</p>
     */
    void computeSov(Map<String, AdMetrics> metricsBySku) {
        String sql = """
                SELECT p.sku_code AS sku_id,
                       SUM(a.impressions) AS total_impressions
                FROM ad_spend a
                JOIN product_catalog p ON a.product_id = p.id
                WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
                GROUP BY p.id, p.sku_code
                """;

        List<Map.Entry<String, Long>> impressionList = duckDbTemplate.aggregate(sql, (rs, rowNum) -> {
            String skuId = rs.getString("sku_id");
            long impressions = rs.getLong("total_impressions");
            return Map.entry(skuId, impressions);
        });

        if (impressionList.isEmpty()) return;

        long maxImpressions = impressionList.stream()
                .mapToLong(Map.Entry::getValue)
                .max()
                .orElse(1L);

        Map<String, Long> impressionsMap = impressionList.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        for (AdMetrics m : metricsBySku.values()) {
            Long impressions = impressionsMap.getOrDefault(m.getSkuId(), 0L);
            if (maxImpressions > 0) {
                m.setSov((double) impressions / maxImpressions * 100.0);
            } else {
                m.setSov(0.0);
            }
            // Use SOV impressions if base impressions not already set
            if (m.getTotalImpressions() == null) {
                m.setTotalImpressions(impressions);
            }
        }
    }

    /**
     * Computes Cannibalization Score for each SKU — the percentage of days
     * in a 7-day window where multiple campaign types overlap.
     *
     * <p>Populates the {@code cannibalizationScore} field on each
     * {@link AdMetrics} in the map.</p>
     */
    void computeCannibalization(Map<String, AdMetrics> metricsBySku) {
        String sql = """
                SELECT p.sku_code AS sku_id,
                       a.date,
                       COUNT(DISTINCT a.campaign_type) AS campaign_count
                FROM ad_spend a
                JOIN product_catalog p ON a.product_id = p.id
                WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
                GROUP BY p.id, p.sku_code, a.date
                """;

        // Group by skuId: count overlapping days vs total days
        Map<String, List<Integer>> dailyCounts = new LinkedHashMap<>();

        duckDbTemplate.aggregate(sql, (rs, rowNum) -> {
            String skuId = rs.getString("sku_id");
            int campaignCount = rs.getInt("campaign_count");
            dailyCounts.computeIfAbsent(skuId, k -> new ArrayList<>()).add(campaignCount);
            return null;
        });

        for (AdMetrics m : metricsBySku.values()) {
            List<Integer> counts = dailyCounts.get(m.getSkuId());
            if (counts == null || counts.isEmpty()) {
                m.setCannibalizationScore(0.0);
            } else {
                long overlapDays = counts.stream().filter(c -> c >= 2).count();
                m.setCannibalizationScore((double) overlapDays / counts.size() * 100.0);
            }
        }
    }

    /**
     * Returns the cannibalization score for a single SKU.
     *
     * @param skuId the SKU identifier
     * @return cannibalization score (0–100), or 0 if no data
     */
    public double getCannibalizationScore(String skuId) {
        String sql = """
                SELECT p.sku_code AS sku_id,
                       a.date,
                       COUNT(DISTINCT a.campaign_type) AS campaign_count
                FROM ad_spend a
                JOIN product_catalog p ON a.product_id = p.id
                WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
                  AND p.sku_code = ?
                GROUP BY p.id, p.sku_code, a.date
                """;

        List<Integer> campaignCounts = duckDbTemplate.query(sql, (rs, rowNum) ->
                rs.getInt("campaign_count"), skuId);

        if (campaignCounts.isEmpty()) {
            return 0.0;
        }

        long overlapDays = campaignCounts.stream().filter(c -> c >= 2).count();
        return (double) overlapDays / campaignCounts.size() * 100.0;
    }

    /**
     * Returns total ad spend for a single SKU over a 30-day window.
     *
     * @param skuId the SKU identifier
     * @return total spend, or 0 if no data
     */
    public double getTotalSpend(String skuId) {
        String sql = """
                SELECT COALESCE(SUM(a.spend), 0) AS total_spend
                FROM ad_spend a
                JOIN product_catalog p ON a.product_id = p.id
                WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '30 days'
                  AND p.sku_code = ?
                """;

        Double result = duckDbTemplate.queryForObject(sql, Double.class, skuId);
        return result != null ? result : 0.0;
    }

    /** Reads a nullable double from a ResultSet, returning null on SQL NULL. */
    private static Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
