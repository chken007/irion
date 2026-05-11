package com.irion.engine;

import com.irion.domain.BusinessAction;
import com.irion.domain.BusinessAction.ActionType;
import com.irion.domain.SkuMetrics;
import com.irion.engine.rule.BuyBoxRule;
import com.irion.engine.rule.WosRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link RuleEngine} with {@link WosRule} and {@link BuyBoxRule}.
 *
 * <p>No Spring context, no DuckDB — the rules are stateless POJOs
 * instantiated directly.  Only the rule evaluation logic is tested.</p>
 */
@DisplayName("RuleEngine")
class RuleEngineTest {

    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RuleEngine(List.of(new WosRule(), new BuyBoxRule()));
    }

    // ── WosRule tests ────────────────────────────────────────────────

    @Test
    @DisplayName("WosRule fires PAUSE_AD when WOS=1.0 and ROAS present")
    void testWosRuleFires() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-001")
                .wos(1.0)
                .roas(new BigDecimal("2.00"))
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).hasSize(1);
        BusinessAction action = actions.get(0);
        assertThat(action.getProductId()).isEqualTo("SKU-001");
        assertThat(action.getActionType()).isEqualTo(ActionType.PAUSE_AD);
        assertThat(action.getRuleName()).isEqualTo("WosRule");
        assertThat(action.getReason()).contains("WOS=1.00");
    }

    @Test
    @DisplayName("WosRule does NOT fire when WOS=3.0")
    void testWosRuleNoFire() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-002")
                .wos(3.0)
                .roas(new BigDecimal("4.00"))
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).isEmpty();
    }

    // ── BuyBoxRule tests ─────────────────────────────────────────────

    @Test
    @DisplayName("BuyBoxRule fires PRICE_ALERT when status=LOST")
    void testBuyBoxRuleFiresOnLost() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-003")
                .wos(4.0)
                .buyBoxStatus("LOST")
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).hasSize(1);
        BusinessAction action = actions.get(0);
        assertThat(action.getProductId()).isEqualTo("SKU-003");
        assertThat(action.getActionType()).isEqualTo(ActionType.PRICE_ALERT);
        assertThat(action.getRuleName()).isEqualTo("BuyBoxRule");
        assertThat(action.getReason()).contains("competitor pricing");
    }

    @Test
    @DisplayName("BuyBoxRule fires PRICE_ALERT when status=SHARED")
    void testBuyBoxRuleFiresOnShared() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-004")
                .wos(5.0)
                .buyBoxStatus("SHARED")
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).hasSize(1);
        BusinessAction action = actions.get(0);
        assertThat(action.getProductId()).isEqualTo("SKU-004");
        assertThat(action.getActionType()).isEqualTo(ActionType.PRICE_ALERT);
        assertThat(action.getRuleName()).isEqualTo("BuyBoxRule");
        assertThat(action.getReason()).contains("monitor competitor");
    }

    // ── Combined rules test ──────────────────────────────────────────

    @Test
    @DisplayName("Both rules fire when WOS=1.0 AND buyBoxStatus=LOST")
    void testMultipleRules() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-005")
                .wos(1.0)
                .roas(new BigDecimal("2.50"))
                .buyBoxStatus("LOST")
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).hasSize(2);
        assertThat(actions).extracting(
                BusinessAction::getActionType,
                BusinessAction::getRuleName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ActionType.PAUSE_AD, "WosRule"),
                        org.assertj.core.groups.Tuple.tuple(ActionType.PRICE_ALERT, "BuyBoxRule"));
    }

    @Test
    @DisplayName("No rules fire when metrics are all healthy")
    void testNoRulesFireWhenHealthy() {
        SkuMetrics metrics = SkuMetrics.builder()
                .skuId("SKU-006")
                .wos(5.0)
                .roas(new BigDecimal("3.00"))
                .buyBoxStatus("WON")
                .build();

        List<BusinessAction> actions = engine.evaluate(metrics);

        assertThat(actions).isEmpty();
    }
}
