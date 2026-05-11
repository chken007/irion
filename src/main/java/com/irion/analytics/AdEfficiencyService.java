package com.irion.analytics;

import com.irion.domain.SkuMetrics;
import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Computes Return on Ad Spend (ROAS) by joining sales_transactions + ad_spend.
 * <p>ROAS = attributed sales / ad spend over 30-day window.</p>
 */
@Slf4j
@Service
public class AdEfficiencyService {

    private final DuckDbTemplate duckDbTemplate;

    public AdEfficiencyService(DuckDbTemplate duckDbTemplate) {
        this.duckDbTemplate = duckDbTemplate;
    }

    public List<SkuMetrics> computeRoas() {
        String sql = """
            SELECT p.sku_code AS sku_id,
                   p.id        AS product_id,
                   p.product_name,
                   p.category,
                   COALESCE(SUM(a.attributed_sales), 0) AS total_revenue,
                   COALESCE(SUM(a.spend), 0)           AS total_ad_spend,
                   CASE
                       WHEN SUM(a.spend) > 0
                           THEN SUM(a.attributed_sales) / SUM(a.spend)
                       ELSE NULL
                   END AS roas
            FROM ad_spend a
            JOIN product_catalog p ON a.product_id = p.id
            WHERE CAST(a.date AS DATE) >= CURRENT_DATE - INTERVAL '30 days'
            GROUP BY p.id, p.sku_code, p.product_name, p.category
            ORDER BY p.sku_code
            """;

        return duckDbTemplate.aggregate(sql, (rs, rowNum) -> SkuMetrics.builder()
                .skuId(rs.getString("sku_id"))
                .productId(rs.getLong("product_id"))
                .productName(rs.getString("product_name"))
                .category(rs.getString("category"))
                .roas(readBigDecimal(rs, "roas"))
                .build());
    }

    private static BigDecimal readBigDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return rs.wasNull() ? null : v;
    }
}
