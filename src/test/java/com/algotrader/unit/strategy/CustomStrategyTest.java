package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import com.algotrader.strategy.impl.CustomStrategy;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CustomStrategy. Verifies identity, entry (always true),
 * exit conditions (target, stop-loss, DTE), no-op adjustment, and no morphing.
 */
class CustomStrategyTest {

    private CustomStrategy customStrategy;
    private PositionalStrategyConfig config;

    @BeforeEach
    void setUp() {
        config = PositionalStrategyConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2026, 3, 27))
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();

        customStrategy = new CustomStrategy("STR-TEST0001", "Test-Custom", config);
    }

    @Nested
    @DisplayName("Identity")
    class Identity {

        @Test
        @DisplayName("getType returns CUSTOM")
        void getTypeReturnsCustom() {
            assertThat(customStrategy.getType()).isEqualTo(StrategyType.CUSTOM);
        }

        @Test
        @DisplayName("Initial status is CREATED")
        void initialStatusIsCreated() {
            assertThat(customStrategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
        }

        @Test
        @DisplayName("Name and ID are set correctly")
        void nameAndIdAreCorrect() {
            assertThat(customStrategy.getId()).isEqualTo("STR-TEST0001");
            assertThat(customStrategy.getName()).isEqualTo("Test-Custom");
        }

        @Test
        @DisplayName("Monitoring interval is 5 minutes (positional)")
        void monitoringIntervalIs5Min() {
            assertThat(customStrategy.getMonitoringInterval()).isEqualTo(java.time.Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("Entry")
    class Entry {

        @Test
        @DisplayName("shouldEnter always returns true")
        void shouldEnterAlwaysTrue() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .build();

            // Use reflection to call protected method
            assertThat(invokeShouldEnter(snapshot)).isTrue();
        }
    }

    @Nested
    @DisplayName("Exit")
    class Exit {

        @Test
        @DisplayName("shouldExit returns false when no positions")
        void shouldExitFalseWhenNoPositions() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .build();

            assertThat(invokeShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("shouldExit returns true when target reached")
        void shouldExitTrueOnTarget() {
            // Add a position and simulate profitable P&L
            Position position = Position.builder()
                    .id("POS-1")
                    .tradingSymbol("NIFTY2632724000CE")
                    .quantity(-50)
                    .averagePrice(BigDecimal.valueOf(200))
                    .lastPrice(BigDecimal.valueOf(80))
                    .unrealizedPnl(BigDecimal.valueOf(6000))
                    .realizedPnl(BigDecimal.ZERO)
                    .build();
            customStrategy.addPosition(position);

            // Set entry premium so target check works
            // entryPremium = 200 * 50 = 10000, targetPercent = 0.5, target = 5000
            // P&L = 6000 >= 5000 → should exit
            setEntryPremium(BigDecimal.valueOf(10000));

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .build();

            assertThat(invokeShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("shouldExit returns true when stop loss hit")
        void shouldExitTrueOnStopLoss() {
            Position position = Position.builder()
                    .id("POS-1")
                    .tradingSymbol("NIFTY2632724000CE")
                    .quantity(-50)
                    .averagePrice(BigDecimal.valueOf(200))
                    .lastPrice(BigDecimal.valueOf(600))
                    .unrealizedPnl(BigDecimal.valueOf(-20000))
                    .realizedPnl(BigDecimal.ZERO)
                    .build();
            customStrategy.addPosition(position);

            // entryPremium = 10000, stopLossMultiplier = 2.0, stopLoss = -20000
            // P&L = -20000 <= -20000 → should exit
            setEntryPremium(BigDecimal.valueOf(10000));

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .build();

            assertThat(invokeShouldExit(snapshot)).isTrue();
        }
    }

    @Nested
    @DisplayName("Adjustment")
    class Adjustment {

        @Test
        @DisplayName("adjust is no-op (does not throw or change state)")
        void adjustIsNoOp() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .build();

            // Should not throw
            invokeAdjust(snapshot);

            // Strategy state unchanged
            assertThat(customStrategy.getPositions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Morphing")
    class Morphing {

        @Test
        @DisplayName("supportedMorphs returns empty list")
        void supportedMorphsEmpty() {
            assertThat(customStrategy.supportedMorphs()).isEmpty();
        }
    }

    // ========================
    // Reflection helpers (access protected methods)
    // ========================

    private boolean invokeShouldEnter(MarketSnapshot snapshot) {
        try {
            var method = CustomStrategy.class.getDeclaredMethod("shouldEnter", MarketSnapshot.class);
            method.setAccessible(true);
            return (boolean) method.invoke(customStrategy, snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean invokeShouldExit(MarketSnapshot snapshot) {
        try {
            var method = CustomStrategy.class.getDeclaredMethod("shouldExit", MarketSnapshot.class);
            method.setAccessible(true);
            return (boolean) method.invoke(customStrategy, snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeAdjust(MarketSnapshot snapshot) {
        try {
            var method = CustomStrategy.class.getDeclaredMethod("adjust", MarketSnapshot.class);
            method.setAccessible(true);
            method.invoke(customStrategy, snapshot);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setEntryPremium(BigDecimal value) {
        try {
            var field = customStrategy.getClass().getSuperclass().getDeclaredField("entryPremium");
            field.setAccessible(true);
            field.set(customStrategy, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
