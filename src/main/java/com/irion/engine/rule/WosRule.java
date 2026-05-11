package com.irion.engine.rule;

import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Weeks-of-Supply rule: pauses advertising when inventory is dangerously low.
 *
 * <p><b>Trigger condition:</b> WOS &lt; 1.5 <em>and</em> ROAS is not {@code null}
 * (meaning the SKU is actively running ads).  When a SKU is about to stock out,
 * continuing to drive traffic via ads wastes spend and risks disappointing
 * customers who cannot purchase.</p>
 *
 * <p><b>Action produced:</b> {@link ActionType#PAUSE_AD}</p>
 */
@Slf4j
@Component
public class WosRule implements Rule {

    /** Default threshold below which the rule fires. */
    static final double WOS_THRESHOLD = 1.5;

    /** Rule name persisted to audit logs and action records. */
    static final String RULE_NAME = "WosRule";

    /**
     * Evaluates the WOS rule against the supplied metrics.
     *
     * @param metrics the computed KPI snapshot for a single SKU; never {@code null}
     * @return a {@code PAUSE_AD} action if WOS is below threshold and the SKU
     *         has active ROAS data; otherwise {@link Optional#empty()}
     */
    @Override
    public Optional<BusinessAction> evaluate(SkuMetrics metrics) {
        if (metrics.getWos() < WOS_THRESHOLD && metrics.getRoas() != null) {
            String reason = String.format(
                    "WOS=%.2f below 1.5 threshold — pause ads to avoid wasting spend on OOS-bound SKU",
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
