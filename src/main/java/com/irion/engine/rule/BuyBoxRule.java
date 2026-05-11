package com.irion.engine.rule;

import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Buy Box status rule: raises a price alert when the SKU is not winning the
 * Buy Box outright.
 *
 * <p><b>Trigger conditions:</b>
 * <ul>
 *   <li>{@code LOST} — the SKU has definitively lost the Buy Box to a competitor.
 *       Emits a {@code PRICE_ALERT} with a strong recommendation to investigate
 *       competitor pricing.</li>
 *   <li>{@code SHARED} — the Buy Box is rotating among sellers.  Emits a milder
 *       {@code PRICE_ALERT} suggesting monitoring of competitor activity.</li>
 * </ul></p>
 *
 * <p>SKUs with status {@code WON} or {@code UNKNOWN} do not trigger this rule.</p>
 */
@Slf4j
@Component
public class BuyBoxRule implements Rule {

    static final String RULE_NAME = "BuyBoxRule";

    /**
     * Evaluates the Buy Box rule against the supplied metrics.
     *
     * @param metrics the computed KPI snapshot for a single SKU; never {@code null}
     * @return a {@code PRICE_ALERT} action if the Buy Box status is
     *         {@code LOST} or {@code SHARED}; otherwise {@link Optional#empty()}
     */
    @Override
    public Optional<BusinessAction> evaluate(SkuMetrics metrics) {
        String status = metrics.getBuyBoxStatus();
        if (status == null) {
            return Optional.empty();
        }

        String reason = switch (status.toUpperCase()) {
            case "LOST" -> "Buy Box lost — investigate competitor pricing";
            case "SHARED" -> "Buy Box shared — monitor competitor activity";
            default -> null;
        };

        if (reason != null) {
            log.info("RULE_FIRED skuId={} rule={} action={} buyBoxStatus={}",
                    metrics.getSkuId(), RULE_NAME, ActionType.PRICE_ALERT, status);

            return Optional.of(BusinessAction.builder()
                    .productId(metrics.getSkuId())
                    .ruleName(RULE_NAME)
                    .actionType(ActionType.PRICE_ALERT)
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .executed(false)
                    .build());
        }

        return Optional.empty();
    }
}
