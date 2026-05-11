package com.irion.engine;

import com.irion.domain.BusinessAction;
import com.irion.domain.SkuMetrics;
import com.irion.engine.rule.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Aggregator that runs all registered {@link Rule} implementations against
 * one or more SKU metric snapshots.
 *
 * <p>Spring automatically discovers every {@code @Component} that implements
 * {@link Rule} and injects them as a {@code List<Rule>}.  Order is determined
 * by Spring's default bean ordering; individual rules can use
 * {@code @Order} if a specific sequence is required.</p>
 *
 * <p>The engine is stateless — it delegates evaluation to each rule and
 * collects only the actions where a rule actually fired.</p>
 */
@Slf4j
@Component
public class RuleEngine {

    private final List<Rule> rules;

    /**
     * Creates the engine with all discovered {@link Rule} beans.
     *
     * @param rules the Spring-managed list of rule implementations
     */
    public RuleEngine(List<Rule> rules) {
        this.rules = rules;
        log.info("RuleEngine initialised with {} rule(s): {}",
                rules.size(),
                rules.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    /**
     * Run all registered rules against a single SKU's metrics snapshot.
     *
     * @param metrics the computed KPI snapshot for one SKU; never {@code null}
     * @return the list of triggered {@link BusinessAction}s (may be empty)
     */
    public List<BusinessAction> evaluate(SkuMetrics metrics) {
        List<BusinessAction> actions = rules.stream()
                .map(r -> r.evaluate(metrics))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        log.debug("Evaluated skuId={}: {} rule(s) fired out of {}",
                metrics.getSkuId(), actions.size(), rules.size());
        return actions;
    }

    /**
     * Run all registered rules against a collection of SKU metric snapshots.
     *
     * @param allMetrics the list of SKU metrics to evaluate; never {@code null}
     * @return the flattened list of all triggered {@link BusinessAction}s
     *         across all SKUs (may be empty)
     */
    public List<BusinessAction> evaluateAll(List<SkuMetrics> allMetrics) {
        List<BusinessAction> actions = allMetrics.stream()
                .flatMap(m -> evaluate(m).stream())
                .toList();

        log.info("Batch evaluation complete: {} SKU(s) evaluated, {} action(s) triggered",
                allMetrics.size(), actions.size());
        return actions;
    }
}
