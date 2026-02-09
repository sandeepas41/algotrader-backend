package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.indicator.IndicatorMetadata;
import com.algotrader.indicator.IndicatorType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for IndicatorMetadata completeness and correctness.
 */
class IndicatorMetadataTest {

    @Test
    @DisplayName("allMetadata covers every IndicatorType")
    void allMetadataCoversEveryType() {
        List<IndicatorMetadata> metadata = IndicatorMetadata.allMetadata();
        Set<IndicatorType> covered =
                metadata.stream().map(IndicatorMetadata::getType).collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(IndicatorType.class));
    }

    @Test
    @DisplayName("every metadata has a display name")
    void everyMetadataHasDisplayName() {
        for (IndicatorMetadata meta : IndicatorMetadata.allMetadata()) {
            assertThat(meta.getDisplayName())
                    .as("Display name for %s", meta.getType())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("every metadata has output fields")
    void everyMetadataHasOutputFields() {
        for (IndicatorMetadata meta : IndicatorMetadata.allMetadata()) {
            assertThat(meta.getOutputFields())
                    .as("Output fields for %s", meta.getType())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("every metadata has default params (possibly empty)")
    void everyMetadataHasDefaultParams() {
        for (IndicatorMetadata meta : IndicatorMetadata.allMetadata()) {
            assertThat(meta.getDefaultParams())
                    .as("Default params for %s", meta.getType())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("RSI has bounded range 0-100")
    void rsiHasBoundedRange() {
        IndicatorMetadata rsi = findByType(IndicatorType.RSI);

        assertThat(rsi.getMinValue()).isEqualTo(0.0);
        assertThat(rsi.getMaxValue()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Stochastic has bounded range 0-100")
    void stochasticHasBoundedRange() {
        IndicatorMetadata stochastic = findByType(IndicatorType.STOCHASTIC);

        assertThat(stochastic.getMinValue()).isEqualTo(0.0);
        assertThat(stochastic.getMaxValue()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Bollinger has three output fields")
    void bollingerHasThreeOutputFields() {
        IndicatorMetadata bollinger = findByType(IndicatorType.BOLLINGER);

        assertThat(bollinger.getOutputFields()).containsExactly("upper", "middle", "lower");
    }

    @Test
    @DisplayName("MACD has value and signal output fields")
    void macdHasTwoOutputFields() {
        IndicatorMetadata macd = findByType(IndicatorType.MACD);

        assertThat(macd.getOutputFields()).containsExactly("value", "signal");
    }

    @Test
    @DisplayName("Stochastic has k and d output fields")
    void stochasticHasKAndDFields() {
        IndicatorMetadata stochastic = findByType(IndicatorType.STOCHASTIC);

        assertThat(stochastic.getOutputFields()).containsExactly("k", "d");
    }

    private static IndicatorMetadata findByType(IndicatorType type) {
        return IndicatorMetadata.allMetadata().stream()
                .filter(m -> m.getType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No metadata for " + type));
    }
}
