package com.irion.engine.rule;

import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Wasted Spend rule: pauses advertising when inventory is effectively
 * exhausted (WOS &lt; 0.5) while ads are still running.
 *
 * <p>This rule is stricter than {@link WosRule} (threshold 0.5 vs 1.5).
 * When a SKU is virtually out of stock, every dollar spent on advertising
 * is waste — it drives traffic to a product that cannot be fulfilled.</p>
 *
 * <p><b>Trigger condition:</b> WOS &lt; 0.5 <em>and</em> ROAS is not
 * {@code null} (meaning ads are active).</p>
 *
 * <p><b>Action produced:</b> {@link ActionType#PAUSE_AD}</p>
 */
@Slf4j
@Component
public class WastedSpendRule implements Rule {

    /** Threshold below which the SKU is effectively out of stock. */
    static final double WOS_THRESHOLD = 0.5;

    /** Rule name persisted to audit logs and action records. */
    static final String RULE_NAME = "WastedSpendRule";

    @Override
    public Optional<BusinessAction> evaluate(SkuMetrics metrics) {
        if (metrics.getWos() < WOS_THRESHOLD && metrics.getRoas() != null) {
            String reason = String.format(
                    "WOS=%.2f below 0.5 — SKU effectively out of stock, immediate ad pause",
                    metrics.getWos());

            log.info("RULE_FIRED skuId={} rule={} action={} wos={}",
                    metrics.getSkuId(), RULE_NAME, ActionType.PAUSE_AD, metrics.getWos());

            return Optional.of(BusinessAction.builder()
                    .productId(metrics.getSkuId())
                    .ruleName(RULE_NAME)
                    .actionType(ActionType.PAUSE_AD)
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .executed(false)
                    .build());
        }
        return Optional.empty();
    }
}
