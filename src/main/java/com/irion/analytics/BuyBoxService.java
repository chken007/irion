package com.irion.analytics;

import com.irion.domain.SkuMetrics;
import com.irion.infra.DuckDbTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes Buy Box win rate from buy_box_events, joined with product_catalog.
 */
@Slf4j
@Service
public class BuyBoxService {

    private final DuckDbTemplate duckDbTemplate;

    public BuyBoxService(DuckDbTemplate duckDbTemplate) {
        this.duckDbTemplate = duckDbTemplate;
    }

    public List<SkuMetrics> computeBuyBoxMetrics() {
        String sql = """
            SELECT p.sku_code      AS sku_id,
                   p.id            AS product_id,
                   p.product_name,
                   p.category,
                   COUNT(*)                                                AS total_observations,
                   SUM(CASE WHEN b.won THEN 1 ELSE 0 END)                  AS buy_box_wins,
                   SUM(CASE WHEN b.won THEN 1 ELSE 0 END) * 100.0
                       / COUNT(*)                                          AS win_rate
            FROM buy_box_events b
            JOIN product_catalog p ON b.product_id = p.id
            WHERE CAST(b.date AS DATE) >= CURRENT_DATE - INTERVAL '7 days'
            GROUP BY p.id, p.sku_code, p.product_name, p.category
            ORDER BY win_rate DESC
            """;

        return duckDbTemplate.aggregate(sql, (rs, rowNum) -> {
            double winRate = rs.getDouble("win_rate");
            return SkuMetrics.builder()
                    .skuId(rs.getString("sku_id"))
                    .productId(rs.getLong("product_id"))
                    .productName(rs.getString("product_name"))
                    .category(rs.getString("category"))
                    .buyBoxWinRate(winRate)
                    .totalObservations(rs.getInt("total_observations"))
                    .buyBoxStatus(classifyBuyBoxStatus(winRate))
                    .build();
        });
    }

    static String classifyBuyBoxStatus(double winRate) {
        if (winRate >= 90.0) return "WON";
        if (winRate >= 50.0) return "SHARED";
        return "LOST";
    }
}
