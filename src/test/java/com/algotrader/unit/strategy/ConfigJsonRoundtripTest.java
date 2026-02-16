package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.mapper.JsonHelper;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.NakedOptionConfig;
import com.algotrader.strategy.impl.StraddleConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that strategy configs can be serialized to JSON and deserialized back
 * to the correct polymorphic subclass via @JsonTypeInfo on BaseStrategyConfig.
 * This is critical for strategy persistence to H2 — configs are stored as JSON
 * in the strategies.config column and must roundtrip without data loss.
 */
class ConfigJsonRoundtripTest {

    @Test
    @DisplayName("StraddleConfig roundtrips through JSON with all fields preserved")
    void straddleConfigRoundtrip() {
        StraddleConfig original = StraddleConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2025, 3, 27))
                .lots(2)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .strikeInterval(BigDecimal.valueOf(50))
                .build();

        String json = JsonHelper.toJson(original);
        assertThat(json).contains("\"@type\":\"STRADDLE\"");

        BaseStrategyConfig restored = JsonHelper.fromJson(json, BaseStrategyConfig.class);
        assertThat(restored).isInstanceOf(StraddleConfig.class);

        StraddleConfig restoredStraddle = (StraddleConfig) restored;
        assertThat(restoredStraddle.getUnderlying()).isEqualTo("NIFTY");
        assertThat(restoredStraddle.getExpiry()).isEqualTo(LocalDate.of(2025, 3, 27));
        assertThat(restoredStraddle.getLots()).isEqualTo(2);
        assertThat(restoredStraddle.getEntryStartTime()).isEqualTo(LocalTime.of(9, 20));
    }

    @Test
    @DisplayName("NakedOptionConfig roundtrips with scalping mode fields")
    void nakedOptionConfigRoundtrip() {
        NakedOptionConfig original = NakedOptionConfig.builder()
                .underlying("BANKNIFTY")
                .expiry(LocalDate.of(2025, 3, 20))
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(100))
                .scalpingMode(true)
                .targetPoints(BigDecimal.valueOf(15))
                .stopLossPoints(BigDecimal.valueOf(30))
                .build();

        String json = JsonHelper.toJson(original);
        assertThat(json).contains("\"@type\":\"NAKED_OPTION\"");

        BaseStrategyConfig restored = JsonHelper.fromJson(json, BaseStrategyConfig.class);
        assertThat(restored).isInstanceOf(NakedOptionConfig.class);

        NakedOptionConfig restoredNaked = (NakedOptionConfig) restored;
        assertThat(restoredNaked.getUnderlying()).isEqualTo("BANKNIFTY");
        assertThat(restoredNaked.isScalpingMode()).isTrue();
        assertThat(restoredNaked.getTargetPoints()).isEqualByComparingTo(BigDecimal.valueOf(15));
        assertThat(restoredNaked.getStopLossPoints()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    @DisplayName("IronCondorConfig roundtrips with spread-specific fields")
    void ironCondorConfigRoundtrip() {
        IronCondorConfig original = IronCondorConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2025, 4, 3))
                .lots(3)
                .strikeInterval(BigDecimal.valueOf(50))
                .build();

        String json = JsonHelper.toJson(original);
        assertThat(json).contains("\"@type\":\"IRON_CONDOR\"");

        BaseStrategyConfig restored = JsonHelper.fromJson(json, BaseStrategyConfig.class);
        assertThat(restored).isInstanceOf(IronCondorConfig.class);
        assertThat(restored.getUnderlying()).isEqualTo("NIFTY");
        assertThat(restored.getLots()).isEqualTo(3);
    }

    @Test
    @DisplayName("PositionalStrategyConfig roundtrips (used by CUSTOM type)")
    void positionalConfigRoundtrip() {
        PositionalStrategyConfig original = PositionalStrategyConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2025, 3, 27))
                .lots(1)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();

        String json = JsonHelper.toJson(original);
        assertThat(json).contains("\"@type\":\"POSITIONAL\"");

        BaseStrategyConfig restored = JsonHelper.fromJson(json, BaseStrategyConfig.class);
        assertThat(restored).isInstanceOf(PositionalStrategyConfig.class);

        PositionalStrategyConfig restoredPos = (PositionalStrategyConfig) restored;
        assertThat(restoredPos.getTargetPercent()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
        assertThat(restoredPos.getStopLossMultiplier()).isEqualByComparingTo(BigDecimal.valueOf(2.0));
        assertThat(restoredPos.getMinDaysToExpiry()).isEqualTo(1);
    }
}
