package com.irion.api;

import com.irion.analytics.BuyBoxService;
import com.irion.analytics.InventoryService;
import com.irion.domain.BusinessAction;
import com.irion.domain.DecisionLog;
import com.irion.domain.SkuMetrics;
import com.irion.engine.ActionService;
import com.irion.engine.RuleEngine;
import com.irion.exception.DataNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST endpoints for rule-engine actions: recommendations, history, and execution.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/actions")
@CrossOrigin(origins = "*")
@Tag(name = "Actions", description = "Rule-based recommendations, action history, and execution")
public class ActionController {

    private final InventoryService inventoryService;
    private final BuyBoxService buyBoxService;
    private final RuleEngine ruleEngine;
    private final ActionService actionService;

    public ActionController(InventoryService inventoryService,
                            BuyBoxService buyBoxService,
                            RuleEngine ruleEngine,
                            ActionService actionService) {
        this.inventoryService = inventoryService;
        this.buyBoxService = buyBoxService;
        this.ruleEngine = ruleEngine;
        this.actionService = actionService;
    }

    @GetMapping("/recommendations")
    @Operation(summary = "Get recommended actions for all SKUs",
               description = "Merges WOS and Buy Box metrics and runs the rule engine against every SKU. "
                           + "Returns all triggered recommendations.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of recommended BusinessActions")
    })
    public ResponseEntity<List<BusinessAction>> getRecommendations() {
        log.info("GET /recommendations");
        List<SkuMetrics> merged = mergeMetrics();
        List<BusinessAction> actions = ruleEngine.evaluateAll(merged);
        return ResponseEntity.ok(actions);
    }

    @GetMapping("/recommendations/{skuId}")
    @Operation(summary = "Get recommended actions for a single SKU",
               description = "Merges WOS and Buy Box metrics for the given SKU and runs the rule engine.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of recommended BusinessActions"),
        @ApiResponse(responseCode = "404", description = "SKU not found")
    })
    public ResponseEntity<List<BusinessAction>> getRecommendationsBySku(
            @Parameter(description = "SKU identifier")
            @PathVariable String skuId) {
        log.info("GET /recommendations/{}", skuId);
        List<SkuMetrics> merged = mergeMetrics();
        SkuMetrics metrics = merged.stream()
                .filter(m -> skuId.equals(m.getSkuId()))
                .findFirst()
                .orElseThrow(() -> new DataNotFoundException("SKU not found: " + skuId));
        return ResponseEntity.ok(ruleEngine.evaluate(metrics));
    }

    @GetMapping("/history")
    @Operation(summary = "Get recent action history for all SKUs",
               description = "Returns the most recent 100 action records across all SKUs.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of BusinessActions")
    })
    public ResponseEntity<List<BusinessAction>> getHistory() {
        log.info("GET /history");
        List<SkuMetrics> allMetrics = mergeMetrics();
        List<BusinessAction> allActions = new ArrayList<>();
        int limit = 100;
        for (SkuMetrics m : allMetrics) {
            if (allActions.size() >= limit) break;
            List<BusinessAction> skuActions = actionService.getActionHistory(m.getSkuId());
            for (BusinessAction a : skuActions) {
                if (allActions.size() >= limit) break;
                allActions.add(a);
            }
        }
        return ResponseEntity.ok(allActions);
    }

    @GetMapping("/history/{skuId}")
    @Operation(summary = "Get action and decision history for a single SKU",
               description = "Returns both the action history and decision audit trail for the given SKU.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Map with 'actions' and 'decisions' lists"),
        @ApiResponse(responseCode = "404", description = "SKU not found")
    })
    public ResponseEntity<java.util.Map<String, Object>> getHistoryBySku(
            @Parameter(description = "SKU identifier")
            @PathVariable String skuId) {
        log.info("GET /history/{}", skuId);
        // Verify SKU exists
        List<SkuMetrics> merged = mergeMetrics();
        merged.stream()
                .filter(m -> skuId.equals(m.getSkuId()))
                .findFirst()
                .orElseThrow(() -> new DataNotFoundException("SKU not found: " + skuId));

        List<BusinessAction> actions = actionService.getActionHistory(skuId);
        List<DecisionLog> decisions = actionService.getDecisionHistory(skuId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("actions", actions);
        result.put("decisions", decisions);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute")
    @Operation(summary = "Execute recommended actions for specified SKUs",
               description = "Runs the rule engine against the given SKU IDs and executes all triggered actions. "
                           + "Each action is recorded and logged with its decision context.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Number of actions executed")
    })
    public ResponseEntity<java.util.Map<String, Object>> executeActions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "List of SKU IDs to evaluate and execute")
            @RequestBody List<String> skuIds) {
        log.info("POST /execute with {} SKU(s)", skuIds.size());

        Map<String, SkuMetrics> metricsMap = mergeMetrics().stream()
                .collect(Collectors.toMap(SkuMetrics::getSkuId, Function.identity()));

        int totalExecuted = 0;
        List<String> errors = new ArrayList<>();

        for (String skuId : skuIds) {
            SkuMetrics metrics = metricsMap.get(skuId);
            if (metrics == null) {
                errors.add("SKU not found: " + skuId);
                continue;
            }
            List<BusinessAction> actions = ruleEngine.evaluate(metrics);
            actionService.executeActions(actions, metrics);
            totalExecuted += actions.size();
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("executed", totalExecuted);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Merges WOS metrics from InventoryService and Buy Box metrics from BuyBoxService
     * into a single list of SkuMetrics per SKU. Non-null fields take priority.
     */
    private List<SkuMetrics> mergeMetrics() {
        Map<String, SkuMetrics> wosMap = inventoryService.getWosForAllSkus().stream()
                .collect(Collectors.toMap(SkuMetrics::getSkuId, Function.identity(), (a, b) -> a));
        Map<String, SkuMetrics> buyBoxMap = buyBoxService.computeBuyBoxMetrics().stream()
                .collect(Collectors.toMap(SkuMetrics::getSkuId, Function.identity(), (a, b) -> a));

        List<SkuMetrics> merged = new ArrayList<>();
        java.util.Set<String> allSkuIds = new java.util.LinkedHashSet<>();
        allSkuIds.addAll(wosMap.keySet());
        allSkuIds.addAll(buyBoxMap.keySet());

        for (String skuId : allSkuIds) {
            SkuMetrics wosM = wosMap.get(skuId);
            SkuMetrics bbM = buyBoxMap.get(skuId);
            merged.add(SkuMetrics.builder()
                    .skuId(skuId)
                    .wos(wosM != null ? wosM.getWos() : 0)
                    .oosRiskIndex(wosM != null ? wosM.getOosRiskIndex() : 0)
                    .roas(wosM != null ? wosM.getRoas() : null)
                    .buyBoxWinRate(bbM != null ? bbM.getBuyBoxWinRate() : null)
                    .buyBoxStatus(bbM != null ? bbM.getBuyBoxStatus() : null)
                    .totalObservations(bbM != null ? bbM.getTotalObservations() : 0)
                    .productName(choose(wosM, bbM, SkuMetrics::getProductName))
                    .category(choose(wosM, bbM, SkuMetrics::getCategory))
                    .build());
        }
        return merged;
    }

    /** Picks the first non-null, non-empty value from two SkuMetrics sources. */
    private static String choose(SkuMetrics a, SkuMetrics b, Function<SkuMetrics, String> getter) {
        String va = a != null ? getter.apply(a) : null;
        if (va != null && !va.isEmpty()) return va;
        String vb = b != null ? getter.apply(b) : null;
        return vb != null && !vb.isEmpty() ? vb : null;
    }
}
