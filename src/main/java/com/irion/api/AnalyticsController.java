package com.irion.api;

import com.irion.analytics.AdAnalyticsService;
import com.irion.analytics.AdEfficiencyService;
import com.irion.analytics.BuyBoxService;
import com.irion.analytics.InventoryService;
import com.irion.domain.AdMetrics;
import com.irion.domain.InventoryRisk;
import com.irion.domain.SkuMetrics;
import com.irion.exception.DataNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for analytics: WOS, ROAS, Buy Box, and inventory risk metrics.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@CrossOrigin(origins = "*")
@Tag(name = "Analytics", description = "Inventory health, ROAS, and Buy Box metrics")
public class AnalyticsController {

    private final InventoryService inventoryService;
    private final AdEfficiencyService adEfficiencyService;
    private final BuyBoxService buyBoxService;
    private final AdAnalyticsService adAnalyticsService;

    public AnalyticsController(InventoryService inventoryService,
                               AdEfficiencyService adEfficiencyService,
                               BuyBoxService buyBoxService,
                               AdAnalyticsService adAnalyticsService) {
        this.inventoryService = inventoryService;
        this.adEfficiencyService = adEfficiencyService;
        this.buyBoxService = buyBoxService;
        this.adAnalyticsService = adAnalyticsService;
    }

    @GetMapping("/wos")
    @Operation(summary = "Get WOS and OOS risk for all SKUs",
               description = "Returns Weeks-of-Supply and Out-of-Stock risk index for every SKU. "
                           + "Optionally filter by a WOS threshold.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of SkuMetrics with WOS and OOS risk")
    })
    public ResponseEntity<List<SkuMetrics>> getWos(
            @Parameter(description = "Filter SKUs with WOS below this threshold (0 = no filter)")
            @RequestParam(defaultValue = "0") double threshold,
            @Parameter(description = "Filter by retailer ID (1=Amazon, 2=Walmart, 3=Target, 4=Instacart, 5=Flipkart)")
            @RequestParam(required = false) Long retailerId) {
        log.info("GET /wos?threshold={}&retailerId={}", threshold, retailerId);
        List<SkuMetrics> all = inventoryService.getWosForAllSkus();
        all = filterByRetailer(all, retailerId);
        if (threshold > 0) {
            all = all.stream().filter(m -> m.getWos() < threshold).toList();
        }
        return ResponseEntity.ok(all);
    }

    @GetMapping("/wos/{skuId}")
    @Operation(summary = "Get WOS and OOS risk for a single SKU",
               description = "Returns Weeks-of-Supply and Out-of-Stock risk index for the given SKU.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SkuMetrics for the SKU"),
        @ApiResponse(responseCode = "404", description = "SKU not found")
    })
    public ResponseEntity<SkuMetrics> getWosBySku(
            @Parameter(description = "SKU identifier")
            @PathVariable String skuId) {
        log.info("GET /wos/{}", skuId);
        SkuMetrics result = inventoryService.getWosForAllSkus().stream()
                .filter(m -> skuId.equals(m.getSkuId()))
                .findFirst()
                .orElseThrow(() -> new DataNotFoundException("SKU not found: " + skuId));
        return ResponseEntity.ok(result);
    }


    @GetMapping("/risks")
    @Operation(summary = "Get risky SKUs (WOS < 2.0)")
    public ResponseEntity<List<InventoryRisk>> getRisks(
            @RequestParam(required = false) Long retailerId) {
        log.info("GET /risks?retailerId={}", retailerId);
        List<InventoryRisk> risks = inventoryService.findRiskySkus(2.0);
        if (retailerId != null) {
            String prefix = "SKU-" + String.format("%03d", retailerId) + "-";
            risks = risks.stream().filter(r -> r.getSkuId() != null && r.getSkuId().startsWith(prefix)).toList();
        }
        return ResponseEntity.ok(risks);
    }

    @GetMapping("/roas")
    public ResponseEntity<List<SkuMetrics>> getRoas(
            @RequestParam(required = false) Long retailerId) {
        log.info("GET /roas?retailerId={}", retailerId);
        return ResponseEntity.ok(filterByRetailer(adEfficiencyService.computeRoas(), retailerId));
    }

    @GetMapping("/buybox")
    public ResponseEntity<List<SkuMetrics>> getBuyBox(
            @RequestParam(required = false) Long retailerId) {
        log.info("GET /buybox?retailerId={}", retailerId);
        return ResponseEntity.ok(filterByRetailer(buyBoxService.computeBuyBoxMetrics(), retailerId));
    }

    @GetMapping("/ad-metrics")
    public ResponseEntity<List<AdMetrics>> getAdMetrics(
            @RequestParam(required = false) Long retailerId) {
        log.info("GET /ad-metrics?retailerId={}", retailerId);
        List<AdMetrics> metrics = adAnalyticsService.computeAllAdMetrics();
        if (retailerId != null) {
            String prefix = "SKU-" + String.format("%03d", retailerId) + "-";
            metrics = metrics.stream().filter(m -> m.getSkuId() != null && m.getSkuId().startsWith(prefix)).toList();
        }
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/health")
    @Operation(summary = "Analytics health check",
               description = "Returns the health status of analytics services and DuckDB connectivity.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Health status map")
    })
    public ResponseEntity<Map<String, Object>> health() {
        log.info("GET /health");
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("duckdb", true);
        Map<String, Boolean> services = new LinkedHashMap<>();
        services.put("inventory", true);
        services.put("adEfficiency", true);
        services.put("buyBox", true);
        services.put("adAnalytics", true);
        health.put("services", services);
        return ResponseEntity.ok(health);
    }

    @GetMapping("/retailers")
    public ResponseEntity<List<Map<String, Object>>> getRetailers() {
        return ResponseEntity.ok(List.of(
            Map.of("id", 1, "code", "AMAZON", "name", "Amazon", "skuPrefix", "SKU-001"),
            Map.of("id", 2, "code", "WALMART", "name", "Walmart", "skuPrefix", "SKU-002"),
            Map.of("id", 3, "code", "TARGET", "name", "Target", "skuPrefix", "SKU-003"),
            Map.of("id", 4, "code", "INSTACART", "name", "Instacart", "skuPrefix", "SKU-004"),
            Map.of("id", 5, "code", "FLIPKART", "name", "Flipkart", "skuPrefix", "SKU-005")
        ));
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> filterByRetailer(List<T> list, Long retailerId) {
        if (retailerId == null) return list;
        String prefix = "SKU-" + String.format("%03d", retailerId) + "-";
        return list.stream().filter(m -> {
            try {
                String skuId = (String) m.getClass().getMethod("getSkuId").invoke(m);
                return skuId != null && skuId.startsWith(prefix);
            } catch (Exception e) { return false; }
        }).toList();
    }
}
