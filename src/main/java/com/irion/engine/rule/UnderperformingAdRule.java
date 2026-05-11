package com.irion.engine.rule;

import com.irion.analytics.AdAnalyticsService;
import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Underperforming Ad rule: pauses advertising when ROAS drops below 1.0
 * and total spend exceeds a minimum threshold.
 *
 * <p>An ROAS below 1.0 means every dollar spent on ads generates less than
 * a dollar in revenue — the campaign is losing money.  The minimum spend
 * threshold ($100 over 30 days) prevents false positives on SKUs with
 * negligible ad activity.</p>
 *
 * <p><b>Trigger condition:</b> ROAS &lt; 1.0 <em>and</em> total_spend &gt; 100
 * (over the trailing 30-day window).</p>
 *
 * <p><b>Action produced:</b> {@link ActionType#PAUSE_AD}</p>
 */
@Slf4j
@Component
public class UnderperformingAdRule implements Rule {

    static final String RULE_NAME = "UnderperformingAdRule";

    /** ROAS threshold below which the ad is underperforming. */
    static final BigDecimal ROAS_THRESHOLD = BigDecimal.ONE;

    /** Minimum ad spend (in currency units) to consider the rule. */
    static final double MIN_SPEND = 100.0;

    private final AdAnalyticsService adAnalyticsService;

    public UnderperformingAdRule(AdAnalyticsService adAnalyticsService) {
        this.adAnalyticsService = adAnalyticsService;
    }

    @Override
    public Optional<BusinessAction> evaluate(SkuMetrics metrics) {
        BigDecimal roas = metrics.getRoas();
        if (roas == null) {
            return Optional.empty();
        }

        if (roas.compareTo(ROAS_THRESHOLD) < 0) {
            double totalSpend = adAnalyticsService.getTotalSpend(metrics.getSkuId());

            if (totalSpend > MIN_SPEND) {
                String reason = String.format(
                        "ROAS=%.2f below 1.0 — ad spend ($%.2f) exceeds revenue, pause ad",
                        roas, totalSpend);

                log.info("RULE_FIRED skuId={} rule={} action={} roas={} totalSpend={}",
                        metrics.getSkuId(), RULE_NAME, ActionType.PAUSE_AD, roas, totalSpend);

                return Optional.of(BusinessAction.builder()
                        .productId(metrics.getSkuId())
                        .ruleName(RULE_NAME)
                        .actionType(ActionType.PAUSE_AD)
                        .reason(reason)
                        .createdAt(LocalDateTime.now())
                        .executed(false)
                        .build());
            }
        }
        return Optional.empty();
    }
}
