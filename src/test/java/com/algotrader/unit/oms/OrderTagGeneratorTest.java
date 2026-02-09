package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.oms.OrderTagGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OrderTagGenerator covering tag format, strategy prefix extraction,
 * action code mapping, sequence numbering, daily reset, and edge cases.
 */
class OrderTagGeneratorTest {

    private OrderTagGenerator orderTagGenerator;

    @BeforeEach
    void setUp() {
        orderTagGenerator = new OrderTagGenerator();
    }

    @Nested
    @DisplayName("Tag Format")
    class TagFormat {

        @Test
        @DisplayName("Tag is exactly 10 characters")
        void tagIsExactly10Chars() {
            String tag = orderTagGenerator.generate("IronCondor1", OrderPriority.STRATEGY_ENTRY);

            assertThat(tag).hasSize(10);
        }

        @Test
        @DisplayName("Tag format is {prefix_3}{action_3}{seq_4}")
        void tagFormatIsCorrect() {
            String tag = orderTagGenerator.generate("IronCondor1", OrderPriority.STRATEGY_ENTRY);

            // IRO + ENT + 0001
            assertThat(tag).isEqualTo("IROENT0001");
        }

        @Test
        @DisplayName("Second tag increments sequence")
        void secondTagIncrementsSequence() {
            orderTagGenerator.generate("IronCondor1", OrderPriority.STRATEGY_ENTRY);
            String second = orderTagGenerator.generate("IronCondor1", OrderPriority.STRATEGY_ENTRY);

            assertThat(second).isEqualTo("IROENT0002");
        }
    }

    @Nested
    @DisplayName("Strategy Prefix")
    class StrategyPrefix {

        @Test
        @DisplayName("Uses first 3 chars of strategy ID uppercased")
        void usesFirst3CharsUppercased() {
            String tag = orderTagGenerator.generate("straddle1", OrderPriority.STRATEGY_ENTRY);

            assertThat(tag).startsWith("STR");
        }

        @Test
        @DisplayName("Short strategy ID pads nothing (uses as-is)")
        void shortStrategyIdUsesAsIs() {
            String tag = orderTagGenerator.generate("IC", OrderPriority.STRATEGY_ENTRY);

            // "IC" -> "IC" (2 chars), then "ENT", then "0001" = 9 chars
            assertThat(tag).startsWith("IC");
            assertThat(tag).contains("ENT");
        }

        @Test
        @DisplayName("Null strategy ID uses GEN prefix")
        void nullStrategyIdUsesGen() {
            String tag = orderTagGenerator.generate(null, OrderPriority.MANUAL);

            assertThat(tag).startsWith("GEN");
        }

        @Test
        @DisplayName("Empty strategy ID uses GEN prefix")
        void emptyStrategyIdUsesGen() {
            String tag = orderTagGenerator.generate("", OrderPriority.MANUAL);

            assertThat(tag).startsWith("GEN");
        }
    }

    @Nested
    @DisplayName("Action Codes")
    class ActionCodes {

        @Test
        @DisplayName("KILL_SWITCH maps to KIL")
        void killSwitchMapsToKil() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.KILL_SWITCH);

            assertThat(tag).contains("KIL");
        }

        @Test
        @DisplayName("RISK_EXIT maps to RSK")
        void riskExitMapsToRsk() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.RISK_EXIT);

            assertThat(tag).contains("RSK");
        }

        @Test
        @DisplayName("STRATEGY_EXIT maps to EXT")
        void strategyExitMapsToExt() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_EXIT);

            assertThat(tag).contains("EXT");
        }

        @Test
        @DisplayName("STRATEGY_ADJUSTMENT maps to ADJ")
        void strategyAdjustmentMapsToAdj() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ADJUSTMENT);

            assertThat(tag).contains("ADJ");
        }

        @Test
        @DisplayName("STRATEGY_ENTRY maps to ENT")
        void strategyEntryMapsToEnt() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);

            assertThat(tag).contains("ENT");
        }

        @Test
        @DisplayName("MANUAL maps to MAN")
        void manualMapsToMan() {
            String tag = orderTagGenerator.generate("STR1", OrderPriority.MANUAL);

            assertThat(tag).contains("MAN");
        }
    }

    @Nested
    @DisplayName("Sequence Numbers")
    class SequenceNumbers {

        @Test
        @DisplayName("Different strategy+action combos have independent counters")
        void independentCounters() {
            String entry = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            String exit = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_EXIT);

            // Both should be seq 0001 since they have different counter keys
            assertThat(entry).endsWith("0001");
            assertThat(exit).endsWith("0001");
        }

        @Test
        @DisplayName("Same strategy+action combo shares counter")
        void sharedCounter() {
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            String third = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);

            assertThat(third).endsWith("0003");
        }

        @Test
        @DisplayName("getCurrentSequence returns current counter value")
        void getCurrentSequenceReturnsValue() {
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);

            int seq = orderTagGenerator.getCurrentSequence("STR1", OrderPriority.STRATEGY_ENTRY);

            assertThat(seq).isEqualTo(2);
        }

        @Test
        @DisplayName("getCurrentSequence returns 0 for unused combo")
        void getCurrentSequenceReturnsZeroForUnused() {
            int seq = orderTagGenerator.getCurrentSequence("UNUSED", OrderPriority.MANUAL);

            assertThat(seq).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Counter Reset")
    class CounterReset {

        @Test
        @DisplayName("resetCounters clears all sequences")
        void resetCountersClearsAll() {
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);

            orderTagGenerator.resetCounters();

            String afterReset = orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            assertThat(afterReset).endsWith("0001");
        }

        @Test
        @DisplayName("resetCounters resets sequence for all combos")
        void resetCountersClearsAllCombos() {
            orderTagGenerator.generate("STR1", OrderPriority.STRATEGY_ENTRY);
            orderTagGenerator.generate("STR2", OrderPriority.STRATEGY_EXIT);

            orderTagGenerator.resetCounters();

            assertThat(orderTagGenerator.getCurrentSequence("STR1", OrderPriority.STRATEGY_ENTRY))
                    .isEqualTo(0);
            assertThat(orderTagGenerator.getCurrentSequence("STR2", OrderPriority.STRATEGY_EXIT))
                    .isEqualTo(0);
        }
    }
}
