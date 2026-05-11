package com.irion.engine.rule;

import com.irion.analytics.AdAnalyticsService;
import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Cannibalization rule: detects when multiple ad campaign types overlap
 * for the same SKU and recommends consolidation.
 *
 * <p>Cannibalization occurs when different campaign types (e.g. Sponsored
 * Products <em>and</em> Sponsored Brands) compete for the same keyword
 * and audience, driving up costs without incremental reach.</p>
 *
 * <p><b>Trigger condition:</b> cannibalization_score &gt; 30 (i.e. more
 * than 30% of days in the 7-day window have overlapping campaign types).</p>
 *
 * <p><b>Action produced:</b> {@link ActionType#CONSOLIDATE_CAMPAIGNS}</p>
 */
@Slf4j
@Component
public class CannibalizationRule implements Rule {

    static final String RULE_NAME = "CannibalizationRule";

    /** Cannibalization score threshold above which the rule fires. */
    static final double CANNIBALIZATION_THRESHOLD = 30.0;

    private final AdAnalyticsService adAnalyticsService;

    public CannibalizationRule(AdAnalyticsService adAnalyticsService) {
        this.adAnalyticsService = adAnalyticsService;
    }

    @Override
    public Optional<BusinessAction> evaluate(SkuMetrics metrics) {
        double score = adAnalyticsService.getCannibalizationScore(metrics.getSkuId());

        if (score > CANNIBALIZATION_THRESHOLD) {
            String reason = String.format(
                    "Cannibalization score=%.1f%% exceeds %s%% threshold — consolidate overlapping campaigns",
                    score, CANNIBALIZATION_THRESHOLD);

            log.info("RULE_FIRED skuId={} rule={} action={} cannibalizationScore={}",
                    metrics.getSkuId(), RULE_NAME, ActionType.CONSOLIDATE_CAMPAIGNS, score);

            return Optional.of(BusinessAction.builder()
                    .productId(metrics.getSkuId())
                    .ruleName(RULE_NAME)
                    .actionType(ActionType.CONSOLIDATE_CAMPAIGNS)
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .executed(false)
                    .build());
        }
        return Optional.empty();
    }
}
