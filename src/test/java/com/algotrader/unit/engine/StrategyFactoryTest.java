package com.algotrader.unit.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.impl.BullCallSpreadConfig;
import com.algotrader.strategy.impl.BullCallSpreadStrategy;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.IronCondorStrategy;
import com.algotrader.strategy.impl.ScalpingConfig;
import com.algotrader.strategy.impl.ScalpingStrategy;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StraddleStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StrategyFactory. Tests implemented types and
 * unimplemented type handling (only CUSTOM remains unimplemented).
 */
class StrategyFactoryTest {

    private StrategyFactory strategyFactory;

    /** Strategy types that have been implemented and should create successfully. */
    private static final Set<StrategyType> IMPLEMENTED_TYPES = Set.of(
            StrategyType.STRADDLE,
            StrategyType.STRANGLE,
            StrategyType.IRON_CONDOR,
            StrategyType.IRON_BUTTERFLY,
            StrategyType.BULL_CALL_SPREAD,
            StrategyType.BEAR_CALL_SPREAD,
            StrategyType.BULL_PUT_SPREAD,
            StrategyType.BEAR_PUT_SPREAD,
            StrategyType.CALENDAR_SPREAD,
            StrategyType.DIAGONAL_SPREAD,
            StrategyType.SCALPING);

    @BeforeEach
    void setUp() {
        strategyFactory = new StrategyFactory();
    }

    @Nested
    @DisplayName("Straddle Creation")
    class StraddleCreation {

        private StraddleConfig straddleConfig;

        @BeforeEach
        void setUp() {
            straddleConfig = StraddleConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .minIV(BigDecimal.valueOf(12))
                    .shiftDeltaThreshold(BigDecimal.valueOf(0.35))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(1.5))
                    .minDaysToExpiry(1)
                    .build();
        }

        @Test
        @DisplayName("Creates StraddleStrategy with correct type and status")
        void createsStraddleStrategy() {
            BaseStrategy strategy = strategyFactory.create(StrategyType.STRADDLE, "NIFTY-Straddle", straddleConfig);

            assertThat(strategy).isInstanceOf(StraddleStrategy.class);
            assertThat(strategy.getType()).isEqualTo(StrategyType.STRADDLE);
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
            assertThat(strategy.getName()).isEqualTo("NIFTY-Straddle");
            assertThat(strategy.getId()).startsWith("STR-");
        }

        @Test
        @DisplayName("Wrong config type throws IllegalArgumentException")
        void wrongConfigTypeThrows() {
            BaseStrategyConfig baseConfig =
                    BaseStrategyConfig.builder().underlying("NIFTY").lots(1).build();

            assertThatThrownBy(() -> strategyFactory.create(StrategyType.STRADDLE, "Test", baseConfig))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("StraddleConfig");
        }
    }

    @Nested
    @DisplayName("Iron Condor Creation")
    class IronCondorCreation {

        private IronCondorConfig ironCondorConfig;

        @BeforeEach
        void setUp() {
            ironCondorConfig = IronCondorConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .callOffset(BigDecimal.valueOf(200))
                    .putOffset(BigDecimal.valueOf(200))
                    .wingWidth(BigDecimal.valueOf(100))
                    .minEntryIV(BigDecimal.valueOf(15))
                    .deltaRollThreshold(BigDecimal.valueOf(0.30))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .build();
        }

        @Test
        @DisplayName("Creates IronCondorStrategy with correct type and status")
        void createsIronCondorStrategy() {
            BaseStrategy strategy = strategyFactory.create(StrategyType.IRON_CONDOR, "NIFTY-IC", ironCondorConfig);

            assertThat(strategy).isInstanceOf(IronCondorStrategy.class);
            assertThat(strategy.getType()).isEqualTo(StrategyType.IRON_CONDOR);
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
            assertThat(strategy.getName()).isEqualTo("NIFTY-IC");
            assertThat(strategy.getId()).startsWith("STR-");
        }
    }

    @Nested
    @DisplayName("Bull Call Spread Creation")
    class BullCallSpreadCreation {

        private BullCallSpreadConfig bullCallSpreadConfig;

        @BeforeEach
        void setUp() {
            bullCallSpreadConfig = BullCallSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .buyOffset(BigDecimal.ZERO)
                    .sellOffset(BigDecimal.valueOf(200))
                    .targetPercent(BigDecimal.valueOf(0.6))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .build();
        }

        @Test
        @DisplayName("Creates BullCallSpreadStrategy with correct type and status")
        void createsBullCallSpreadStrategy() {
            BaseStrategy strategy =
                    strategyFactory.create(StrategyType.BULL_CALL_SPREAD, "NIFTY-BCS", bullCallSpreadConfig);

            assertThat(strategy).isInstanceOf(BullCallSpreadStrategy.class);
            assertThat(strategy.getType()).isEqualTo(StrategyType.BULL_CALL_SPREAD);
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
            assertThat(strategy.getName()).isEqualTo("NIFTY-BCS");
            assertThat(strategy.getId()).startsWith("STR-");
        }
    }

    @Nested
    @DisplayName("Scalping Creation")
    class ScalpingCreation {

        private ScalpingConfig scalpingConfig;

        @BeforeEach
        void setUp() {
            scalpingConfig = ScalpingConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .optionType("CE")
                    .strike(BigDecimal.valueOf(22000))
                    .side(OrderSide.BUY)
                    .targetPoints(BigDecimal.valueOf(20))
                    .stopLossPoints(BigDecimal.valueOf(10))
                    .maxHoldDuration(Duration.ofMinutes(10))
                    .build();
        }

        @Test
        @DisplayName("Creates ScalpingStrategy with correct type and status")
        void createsScalpingStrategy() {
            BaseStrategy strategy = strategyFactory.create(StrategyType.SCALPING, "NIFTY-Scalp", scalpingConfig);

            assertThat(strategy).isInstanceOf(ScalpingStrategy.class);
            assertThat(strategy.getType()).isEqualTo(StrategyType.SCALPING);
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
            assertThat(strategy.getName()).isEqualTo("NIFTY-Scalp");
            assertThat(strategy.getId()).startsWith("STR-");
        }
    }

    @Nested
    @DisplayName("Unimplemented Types")
    class UnimplementedTypes {

        private BaseStrategyConfig config;

        @BeforeEach
        void setUp() {
            config = BaseStrategyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .build();
        }

        @Test
        @DisplayName("Unimplemented strategy types throw UnsupportedOperationException")
        void unimplementedTypesThrow() {
            for (StrategyType type : StrategyType.values()) {
                if (IMPLEMENTED_TYPES.contains(type)) {
                    continue;
                }
                assertThatThrownBy(() -> strategyFactory.create(type, "Test", config))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining(type.name());
            }
        }

        @Test
        @DisplayName("CUSTOM type throws UnsupportedOperationException referencing CUSTOM")
        void customTypeThrows() {
            assertThatThrownBy(() -> strategyFactory.create(StrategyType.CUSTOM, "Test", config))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("CUSTOM");
        }
    }
}
