package com.algotrader.unit.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.domain.enums.PositionSizingType;
import com.algotrader.domain.model.PositionSizingContext;
import com.algotrader.margin.PositionSizerFactory;
import com.algotrader.margin.PositionSizingConfig;
import com.algotrader.margin.impl.FixedLotSizer;
import com.algotrader.margin.impl.PercentageOfCapitalSizer;
import com.algotrader.margin.impl.RiskBasedSizer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all PositionSizer implementations (FixedLot, PercentageOfCapital,
 * RiskBased) and the PositionSizerFactory.
 */
class PositionSizerTest {

    private PositionSizingConfig positionSizingConfig;

    @BeforeEach
    void setUp() {
        positionSizingConfig = new PositionSizingConfig();
    }

    private PositionSizingContext contextWith(
            BigDecimal availableMargin, BigDecimal totalCapital, BigDecimal marginPerLot, int maxLots) {
        return PositionSizingContext.builder()
                .availableMargin(availableMargin)
                .totalCapital(totalCapital)
                .marginPerLot(marginPerLot)
                .lotSize(75)
                .underlying("NIFTY")
                .maxLotsAllowed(maxLots)
                .build();
    }

    // ==============================
    // FIXED LOT SIZER
    // ==============================

    @Nested
    @DisplayName("FixedLotSizer")
    class FixedLotSizerTests {

        private FixedLotSizer fixedLotSizer;

        @BeforeEach
        void setUp() {
            fixedLotSizer = new FixedLotSizer(positionSizingConfig);
        }

        @Test
        @DisplayName("Returns configured fixed lots when margin is sufficient")
        void returnsConfiguredLots() {
            positionSizingConfig.setFixedLots(3);

            int lots = fixedLotSizer.calculateLots(
                    contextWith(new BigDecimal("500000"), new BigDecimal("1000000"), new BigDecimal("100000"), 10));

            assertThat(lots).isEqualTo(3);
        }

        @Test
        @DisplayName("Reduces to affordable lots when margin is insufficient")
        void reducesWhenMarginInsufficient() {
            positionSizingConfig.setFixedLots(5);

            // Available 200000, margin per lot 100000 -> can afford 2
            int lots = fixedLotSizer.calculateLots(
                    contextWith(new BigDecimal("200000"), new BigDecimal("1000000"), new BigDecimal("100000"), 10));

            assertThat(lots).isEqualTo(2);
        }

        @Test
        @DisplayName("Caps at maxLotsAllowed")
        void capsAtMaxLots() {
            positionSizingConfig.setFixedLots(10);

            int lots = fixedLotSizer.calculateLots(
                    contextWith(new BigDecimal("5000000"), new BigDecimal("10000000"), new BigDecimal("100000"), 5));

            assertThat(lots).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns zero when no margin available")
        void zeroWhenNoMargin() {
            positionSizingConfig.setFixedLots(2);

            int lots = fixedLotSizer.calculateLots(
                    contextWith(new BigDecimal("50000"), new BigDecimal("1000000"), new BigDecimal("100000"), 10));

            assertThat(lots).isEqualTo(0);
        }

        @Test
        @DisplayName("Reports correct sizing type")
        void correctType() {
            assertThat(fixedLotSizer.getType()).isEqualTo(PositionSizingType.FIXED_LOTS);
        }
    }

    // ==============================
    // PERCENTAGE OF CAPITAL SIZER
    // ==============================

    @Nested
    @DisplayName("PercentageOfCapitalSizer")
    class PercentageOfCapitalSizerTests {

        private PercentageOfCapitalSizer percentageOfCapitalSizer;

        @BeforeEach
        void setUp() {
            percentageOfCapitalSizer = new PercentageOfCapitalSizer(positionSizingConfig);
        }

        @Test
        @DisplayName("Computes correct lot count from capital percentage")
        void computesCorrectLots() {
            positionSizingConfig.setCapitalPercentage(new BigDecimal("10.0"));

            // 10% of 1000000 = 100000, margin per lot = 50000 -> 2 lots
            int lots = percentageOfCapitalSizer.calculateLots(
                    contextWith(new BigDecimal("500000"), new BigDecimal("1000000"), new BigDecimal("50000"), 10));

            assertThat(lots).isEqualTo(2);
        }

        @Test
        @DisplayName("Enforces minimum of 1 lot")
        void minimumOneLot() {
            positionSizingConfig.setCapitalPercentage(new BigDecimal("1.0"));

            // 1% of 100000 = 1000, margin per lot = 50000 -> 0 lots, but min 1
            int lots = percentageOfCapitalSizer.calculateLots(
                    contextWith(new BigDecimal("500000"), new BigDecimal("100000"), new BigDecimal("50000"), 10));

            assertThat(lots).isEqualTo(1);
        }

        @Test
        @DisplayName("Reduces to affordable lots when margin insufficient")
        void reducesForMargin() {
            positionSizingConfig.setCapitalPercentage(new BigDecimal("50.0"));

            // 50% of 1000000 = 500000, margin per lot = 100000 -> 5 lots
            // But available margin = 200000 -> only 2 lots affordable
            int lots = percentageOfCapitalSizer.calculateLots(
                    contextWith(new BigDecimal("200000"), new BigDecimal("1000000"), new BigDecimal("100000"), 10));

            assertThat(lots).isEqualTo(2);
        }

        @Test
        @DisplayName("Caps at maxLotsAllowed")
        void capsAtMaxLots() {
            positionSizingConfig.setCapitalPercentage(new BigDecimal("50.0"));

            // 50% of 1000000 = 500000 / 50000 = 10 lots, but max is 3
            int lots = percentageOfCapitalSizer.calculateLots(
                    contextWith(new BigDecimal("5000000"), new BigDecimal("1000000"), new BigDecimal("50000"), 3));

            assertThat(lots).isEqualTo(3);
        }

        @Test
        @DisplayName("Reports correct sizing type")
        void correctType() {
            assertThat(percentageOfCapitalSizer.getType()).isEqualTo(PositionSizingType.PERCENTAGE_OF_CAPITAL);
        }
    }

    // ==============================
    // RISK BASED SIZER
    // ==============================

    @Nested
    @DisplayName("RiskBasedSizer")
    class RiskBasedSizerTests {

        private RiskBasedSizer riskBasedSizer;

        @BeforeEach
        void setUp() {
            riskBasedSizer = new RiskBasedSizer(positionSizingConfig);
        }

        @Test
        @DisplayName("Sizes based on maximum acceptable loss per trade")
        void sizesBasedOnRisk() {
            positionSizingConfig.setRiskPercentage(new BigDecimal("2.0"));

            // 2% of 1000000 = 20000, max loss per lot = 5000 -> 4 lots
            PositionSizingContext context = PositionSizingContext.builder()
                    .availableMargin(new BigDecimal("500000"))
                    .totalCapital(new BigDecimal("1000000"))
                    .marginPerLot(new BigDecimal("100000"))
                    .maxLossPerLot(new BigDecimal("5000"))
                    .lotSize(75)
                    .underlying("NIFTY")
                    .maxLotsAllowed(10)
                    .build();

            int lots = riskBasedSizer.calculateLots(context);

            assertThat(lots).isEqualTo(4);
        }

        @Test
        @DisplayName("Falls back to 1 lot when maxLossPerLot is null")
        void fallbackWhenNullLoss() {
            PositionSizingContext context = PositionSizingContext.builder()
                    .availableMargin(new BigDecimal("500000"))
                    .totalCapital(new BigDecimal("1000000"))
                    .marginPerLot(new BigDecimal("100000"))
                    .maxLossPerLot(null)
                    .lotSize(75)
                    .underlying("NIFTY")
                    .maxLotsAllowed(10)
                    .build();

            int lots = riskBasedSizer.calculateLots(context);

            assertThat(lots).isEqualTo(1);
        }

        @Test
        @DisplayName("Falls back to 1 lot when maxLossPerLot is zero")
        void fallbackWhenZeroLoss() {
            PositionSizingContext context = PositionSizingContext.builder()
                    .availableMargin(new BigDecimal("500000"))
                    .totalCapital(new BigDecimal("1000000"))
                    .marginPerLot(new BigDecimal("100000"))
                    .maxLossPerLot(BigDecimal.ZERO)
                    .lotSize(75)
                    .underlying("NIFTY")
                    .maxLotsAllowed(10)
                    .build();

            int lots = riskBasedSizer.calculateLots(context);

            assertThat(lots).isEqualTo(1);
        }

        @Test
        @DisplayName("Reduces to affordable lots when margin is insufficient")
        void reducesForMargin() {
            positionSizingConfig.setRiskPercentage(new BigDecimal("5.0"));

            // 5% of 1000000 = 50000, max loss per lot = 5000 -> 10 lots
            // But available margin = 200000, margin per lot = 100000 -> max 2 affordable
            PositionSizingContext context = PositionSizingContext.builder()
                    .availableMargin(new BigDecimal("200000"))
                    .totalCapital(new BigDecimal("1000000"))
                    .marginPerLot(new BigDecimal("100000"))
                    .maxLossPerLot(new BigDecimal("5000"))
                    .lotSize(75)
                    .underlying("NIFTY")
                    .maxLotsAllowed(10)
                    .build();

            int lots = riskBasedSizer.calculateLots(context);

            assertThat(lots).isEqualTo(2);
        }

        @Test
        @DisplayName("Caps at maxLotsAllowed")
        void capsAtMaxLots() {
            positionSizingConfig.setRiskPercentage(new BigDecimal("10.0"));

            // 10% of 1000000 = 100000, max loss per lot = 5000 -> 20 lots, capped at 3
            PositionSizingContext context = PositionSizingContext.builder()
                    .availableMargin(new BigDecimal("5000000"))
                    .totalCapital(new BigDecimal("1000000"))
                    .marginPerLot(new BigDecimal("100000"))
                    .maxLossPerLot(new BigDecimal("5000"))
                    .lotSize(75)
                    .underlying("NIFTY")
                    .maxLotsAllowed(3)
                    .build();

            int lots = riskBasedSizer.calculateLots(context);

            assertThat(lots).isEqualTo(3);
        }

        @Test
        @DisplayName("Reports correct sizing type")
        void correctType() {
            assertThat(riskBasedSizer.getType()).isEqualTo(PositionSizingType.RISK_BASED);
        }
    }

    // ==============================
    // POSITION SIZER FACTORY
    // ==============================

    @Nested
    @DisplayName("PositionSizerFactory")
    class PositionSizerFactoryTests {

        @Test
        @DisplayName("Resolves FixedLotSizer for FIXED_LOTS type")
        void resolvesFixedLot() {
            PositionSizerFactory positionSizerFactory = new PositionSizerFactory(List.of(
                    new FixedLotSizer(positionSizingConfig),
                    new PercentageOfCapitalSizer(positionSizingConfig),
                    new RiskBasedSizer(positionSizingConfig)));

            assertThat(positionSizerFactory.getSizer(PositionSizingType.FIXED_LOTS))
                    .isInstanceOf(FixedLotSizer.class);
        }

        @Test
        @DisplayName("Resolves PercentageOfCapitalSizer for PERCENTAGE_OF_CAPITAL type")
        void resolvesPercentage() {
            PositionSizerFactory positionSizerFactory = new PositionSizerFactory(List.of(
                    new FixedLotSizer(positionSizingConfig),
                    new PercentageOfCapitalSizer(positionSizingConfig),
                    new RiskBasedSizer(positionSizingConfig)));

            assertThat(positionSizerFactory.getSizer(PositionSizingType.PERCENTAGE_OF_CAPITAL))
                    .isInstanceOf(PercentageOfCapitalSizer.class);
        }

        @Test
        @DisplayName("Resolves RiskBasedSizer for RISK_BASED type")
        void resolvesRiskBased() {
            PositionSizerFactory positionSizerFactory = new PositionSizerFactory(List.of(
                    new FixedLotSizer(positionSizingConfig),
                    new PercentageOfCapitalSizer(positionSizingConfig),
                    new RiskBasedSizer(positionSizingConfig)));

            assertThat(positionSizerFactory.getSizer(PositionSizingType.RISK_BASED))
                    .isInstanceOf(RiskBasedSizer.class);
        }

        @Test
        @DisplayName("Throws on unknown type when no sizer registered")
        void throwsOnUnknownType() {
            PositionSizerFactory positionSizerFactory = new PositionSizerFactory(List.of());

            assertThatThrownBy(() -> positionSizerFactory.getSizer(PositionSizingType.FIXED_LOTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No position sizer found");
        }
    }
}
