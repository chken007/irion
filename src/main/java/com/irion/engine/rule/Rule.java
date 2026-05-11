package com.irion.engine.rule;

import com.irion.domain.BusinessAction;
import com.irion.domain.SkuMetrics;

import java.util.Optional;

/**
 * A single business rule that evaluates a SKU's metrics and recommends an action
 * when its condition is satisfied.
 *
 * <p>Implementations are Spring {@code @Component} beans automatically
 * discovered by the {@code RuleEngine}.  Each rule is stateless and thread-safe.</p>
 *
 * <p>Rules follow the Single-Responsibility Principle — one rule, one concern.
 * Returning {@link Optional#empty()} means the rule did not fire; the caller
 * filters those out and collects only the triggered actions.</p>
 */
@FunctionalInterface
public interface Rule {

    /**
     * Evaluate this rule against the given SKU metrics snapshot.
     *
     * @param metrics the computed KPI snapshot for a single SKU; never {@code null}
     * @return a {@link BusinessAction} if the rule fires, otherwise
     *         {@link Optional#empty()}
     */
    Optional<BusinessAction> evaluate(SkuMetrics metrics);
}
