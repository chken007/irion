package com.irion.analytics;

import com.irion.domain.InventoryRisk;
import com.irion.domain.SkuMetrics;
import com.irion.infra.DuckDbTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryService")
class InventoryServiceTest {

    private DuckDbTemplate duckDbTemplate;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Connection conn = DriverManager.getConnection("jdbc:duckdb::memory:");
        DataSource dataSource = new SingleConnectionDataSource(conn, true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        duckDbTemplate = new DuckDbTemplate(jdbcTemplate);
        inventoryService = new InventoryService(duckDbTemplate);

        // Create tables matching new schema: inventory_snapshots, sales_transactions, product_catalog
        duckDbTemplate.execute("""
            CREATE TABLE product_catalog (
                id BIGINT, sku_code VARCHAR, product_name VARCHAR, category VARCHAR
            )
            """);
        duckDbTemplate.execute("""
            CREATE TABLE inventory_snapshots (
                product_id BIGINT, snapshot_date DATE, stock_on_hand INTEGER
            )
            """);
        duckDbTemplate.execute("""
            CREATE TABLE sales_transactions (
                product_id BIGINT, date DATE, qty INTEGER
            )
            """);
    }

    private void insertProduct(long id, String skuCode, String name, String cat) {
        duckDbTemplate.execute(String.format(
            "INSERT INTO product_catalog VALUES (%d, '%s', '%s', '%s')", id, skuCode, name, cat));
    }

    private void insertInventory(long productId, LocalDate date, int stock) {
        duckDbTemplate.execute(String.format(
            "INSERT INTO inventory_snapshots VALUES (%d, '%s', %d)", productId, date, stock));
    }

    private void insertSales(long productId, int numDays, double qtyPerDay) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < numDays; i++) {
            LocalDate d = today.minusDays(i);
            duckDbTemplate.execute(String.format(
                "INSERT INTO sales_transactions VALUES (%d, '%s', %d)", productId, d, (int) qtyPerDay));
        }
    }

    @Test
    @DisplayName("WOS calculation: inventory=420, sales=70/day → WOS=6.0")
    void testWosCalculation() {
        insertProduct(1L, "SKU-A", "Product A", "Cat");
        insertInventory(1L, LocalDate.now(), 420);
        insertSales(1L, 14, 70.0);

        List<SkuMetrics> results = inventoryService.getWosForAllSkus();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSkuId()).isEqualTo("SKU-A");
        assertThat(results.get(0).getWos()).isCloseTo(6.0, within(0.01));
    }

    @Test
    @DisplayName("OOS risk CRITICAL when WOS < 1.0")
    void testOosRiskCritical() {
        insertProduct(2L, "SKU-B", "Product B", "Cat");
        insertInventory(2L, LocalDate.now(), 50);
        insertSales(2L, 14, 100.0);

        List<InventoryRisk> results = inventoryService.findRiskySkus(2.0);
        assertThat(results).hasSize(1);
        InventoryRisk risk = results.get(0);
        assertThat(risk.getSkuId()).isEqualTo("SKU-B");
        assertThat(risk.getOosRiskIndex()).isCloseTo(95.0, within(0.01));
        assertThat(risk.getRiskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("findRiskySkus with threshold=2.0 filters correctly")
    void testFindRiskySkus() {
        // SKU-LOW: WOS≈0.7  SKU-MID: WOS≈2.0  SKU-HIGH: WOS≈5.0
        insertProduct(10L, "SKU-LOW", "Low", "Cat");
        insertInventory(10L, LocalDate.now(), 70);
        insertSales(10L, 14, 100.0);

        insertProduct(20L, "SKU-MID", "Mid", "Cat");
        insertInventory(20L, LocalDate.now(), 200);
        insertSales(20L, 14, 100.0);

        insertProduct(30L, "SKU-HIGH", "High", "Cat");
        insertInventory(30L, LocalDate.now(), 500);
        insertSales(30L, 14, 100.0);

        List<InventoryRisk> results = inventoryService.findRiskySkus(2.0);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSkuId()).isEqualTo("SKU-LOW");
    }

    @Test
    @DisplayName("Empty table returns empty list without error")
    void testNoData() {
        List<SkuMetrics> all = inventoryService.getWosForAllSkus();
        List<InventoryRisk> risky = inventoryService.findRiskySkus(2.0);
        assertThat(all).isEmpty();
        assertThat(risky).isEmpty();
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
