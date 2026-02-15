package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import com.algotrader.strategy.impl.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StrategyFactory verifying all strategy types are wired correctly.
 * Ensures every StrategyType enum value (except CUSTOM) can be instantiated.
 */
class StrategyFactoryTest {

    private StrategyFactory strategyFactory;

    @BeforeEach
    void setUp() {
        strategyFactory = new StrategyFactory();
    }

    @Test
    @DisplayName("Creates StrangleStrategy for STRANGLE type")
    void createsStrangle() {
        StrangleConfig config = StrangleConfig.builder()
                .underlying("NIFTY")
                .callOffset(BigDecimal.valueOf(200))
                .putOffset(BigDecimal.valueOf(200))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.STRANGLE, "Test-Strangle", config);

        assertThat(strategy).isInstanceOf(StrangleStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.STRANGLE);
    }

    @Test
    @DisplayName("Creates BearPutSpreadStrategy for BEAR_PUT_SPREAD type")
    void createsBearPutSpread() {
        BearPutSpreadConfig config = BearPutSpreadConfig.builder()
                .underlying("NIFTY")
                .buyOffset(BigDecimal.ZERO)
                .sellOffset(BigDecimal.valueOf(-200))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.BEAR_PUT_SPREAD, "Test-BPS", config);

        assertThat(strategy).isInstanceOf(BearPutSpreadStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.BEAR_PUT_SPREAD);
    }

    @Test
    @DisplayName("Creates BullPutSpreadStrategy for BULL_PUT_SPREAD type")
    void createsBullPutSpread() {
        BullPutSpreadConfig config = BullPutSpreadConfig.builder()
                .underlying("NIFTY")
                .sellOffset(BigDecimal.valueOf(-100))
                .buyOffset(BigDecimal.valueOf(-300))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.BULL_PUT_SPREAD, "Test-BuPS", config);

        assertThat(strategy).isInstanceOf(BullPutSpreadStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.BULL_PUT_SPREAD);
    }

    @Test
    @DisplayName("Creates BearCallSpreadStrategy for BEAR_CALL_SPREAD type")
    void createsBearCallSpread() {
        BearCallSpreadConfig config = BearCallSpreadConfig.builder()
                .underlying("NIFTY")
                .sellOffset(BigDecimal.valueOf(100))
                .buyOffset(BigDecimal.valueOf(300))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.BEAR_CALL_SPREAD, "Test-BCS", config);

        assertThat(strategy).isInstanceOf(BearCallSpreadStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.BEAR_CALL_SPREAD);
    }

    @Test
    @DisplayName("Creates IronButterflyStrategy for IRON_BUTTERFLY type")
    void createsIronButterfly() {
        IronButterflyConfig config = IronButterflyConfig.builder()
                .underlying("NIFTY")
                .wingWidth(BigDecimal.valueOf(200))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.IRON_BUTTERFLY, "Test-IB", config);

        assertThat(strategy).isInstanceOf(IronButterflyStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.IRON_BUTTERFLY);
    }

    @Test
    @DisplayName("Creates CalendarSpreadStrategy for CALENDAR_SPREAD type")
    void createsCalendarSpread() {
        CalendarSpreadConfig config = CalendarSpreadConfig.builder()
                .underlying("NIFTY")
                .strikeOffset(BigDecimal.ZERO)
                .optionType("CE")
                .nearExpiry(LocalDate.of(2025, 2, 13))
                .farExpiry(LocalDate.of(2025, 2, 27))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.CALENDAR_SPREAD, "Test-Cal", config);

        assertThat(strategy).isInstanceOf(CalendarSpreadStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.CALENDAR_SPREAD);
    }

    @Test
    @DisplayName("Creates DiagonalSpreadStrategy for DIAGONAL_SPREAD type")
    void createsDiagonalSpread() {
        DiagonalSpreadConfig config = DiagonalSpreadConfig.builder()
                .underlying("NIFTY")
                .nearStrikeOffset(BigDecimal.valueOf(200))
                .farStrikeOffset(BigDecimal.ZERO)
                .optionType("CE")
                .nearExpiry(LocalDate.of(2025, 2, 13))
                .farExpiry(LocalDate.of(2025, 2, 27))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.DIAGONAL_SPREAD, "Test-Diag", config);

        assertThat(strategy).isInstanceOf(DiagonalSpreadStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.DIAGONAL_SPREAD);
    }

    @Test
    @DisplayName("CUSTOM type creates CustomStrategy")
    void customTypeCreatesCustomStrategy() {
        PositionalStrategyConfig config = PositionalStrategyConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.CUSTOM, "Test-Custom", config);

        assertThat(strategy).isInstanceOf(CustomStrategy.class);
        assertThat(strategy.getType()).isEqualTo(StrategyType.CUSTOM);
    }

    @Test
    @DisplayName("Wrong config type throws IllegalArgumentException")
    void wrongConfigTypeThrows() {
        StraddleConfig straddleConfig =
                StraddleConfig.builder().underlying("NIFTY").build();

        assertThatThrownBy(() -> strategyFactory.create(StrategyType.STRANGLE, "Test", straddleConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StrangleConfig");
    }

    @Test
    @DisplayName("Generated strategy ID has STR- prefix and 8-char UUID")
    void generatedIdFormat() {
        StrangleConfig config = StrangleConfig.builder()
                .underlying("NIFTY")
                .callOffset(BigDecimal.valueOf(200))
                .putOffset(BigDecimal.valueOf(200))
                .build();

        BaseStrategy strategy = strategyFactory.create(StrategyType.STRANGLE, "Test", config);

        assertThat(strategy.getId()).startsWith("STR-");
        assertThat(strategy.getId()).hasSize(12); // "STR-" + 8 chars
    }
}
