package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.broker.mapper.KitePositionMapper;
import com.algotrader.domain.enums.PositionType;
import com.algotrader.domain.model.Position;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KitePositionMapper}.
 *
 * <p>Verifies Kite SDK Position (with mixed field types: String instrumentToken,
 * int netQuantity, double averagePrice, Double unrealised) -> domain Position mapping.
 */
class KitePositionMapperTest {

    private KitePositionMapper kitePositionMapper;

    @BeforeEach
    void setUp() {
        kitePositionMapper = new KitePositionMapper();
    }

    @Nested
    @DisplayName("Kite Position -> Domain Position (toDomain)")
    class ToDomainTests {

        @Test
        @DisplayName("maps a long position correctly")
        void mapsLongPosition() {
            com.zerodhatech.models.Position kitePos = new com.zerodhatech.models.Position();
            kitePos.instrumentToken = "256265";
            kitePos.tradingSymbol = "NIFTY24FEB22000CE";
            kitePos.exchange = "NFO";
            kitePos.netQuantity = 50;
            kitePos.averagePrice = 150.25;
            kitePos.lastPrice = 155.50;
            kitePos.unrealised = 262.50;
            kitePos.realised = 0.0;
            kitePos.m2m = 262.50;
            kitePos.overnightQuantity = 0;
            kitePos.product = "NRML";

            Position position = kitePositionMapper.toDomain(kitePos);

            assertThat(position.getInstrumentToken()).isEqualTo(256265L);
            assertThat(position.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
            assertThat(position.getExchange()).isEqualTo("NFO");
            assertThat(position.getQuantity()).isEqualTo(50);
            assertThat(position.getType()).isEqualTo(PositionType.LONG);
            assertThat(position.getAveragePrice()).isEqualByComparingTo(new BigDecimal("150.25"));
            assertThat(position.getLastPrice()).isEqualByComparingTo(new BigDecimal("155.5"));
            assertThat(position.getUnrealizedPnl()).isEqualByComparingTo(new BigDecimal("262.5"));
            assertThat(position.getRealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(position.getM2m()).isEqualByComparingTo(new BigDecimal("262.5"));
            assertThat(position.getOvernightQuantity()).isEqualTo(0);
            assertThat(position.getLastUpdated()).isNotNull();
        }

        @Test
        @DisplayName("maps a short position correctly (negative quantity)")
        void mapsShortPosition() {
            com.zerodhatech.models.Position kitePos = new com.zerodhatech.models.Position();
            kitePos.instrumentToken = "256266";
            kitePos.tradingSymbol = "NIFTY24FEB22000PE";
            kitePos.exchange = "NFO";
            kitePos.netQuantity = -75;
            kitePos.averagePrice = 80.00;
            kitePos.lastPrice = 85.00;
            kitePos.unrealised = -375.0;
            kitePos.realised = 0.0;
            kitePos.m2m = -375.0;
            kitePos.overnightQuantity = -75;

            Position position = kitePositionMapper.toDomain(kitePos);

            assertThat(position.getQuantity()).isEqualTo(-75);
            assertThat(position.getType()).isEqualTo(PositionType.SHORT);
            assertThat(position.getOvernightQuantity()).isEqualTo(-75);
        }

        @Test
        @DisplayName("handles null Kite position gracefully")
        void handlesNullPosition() {
            assertThat(kitePositionMapper.toDomain(null)).isNull();
        }

        @Test
        @DisplayName("handles null Double fields (unrealised, realised, etc.)")
        void handlesNullDoubleFields() {
            com.zerodhatech.models.Position kitePos = new com.zerodhatech.models.Position();
            kitePos.instrumentToken = "12345";
            kitePos.tradingSymbol = "TEST";
            kitePos.netQuantity = 0;
            kitePos.averagePrice = 0.0;
            kitePos.lastPrice = null;
            kitePos.unrealised = null;
            kitePos.realised = null;
            kitePos.m2m = null;

            Position position = kitePositionMapper.toDomain(kitePos);

            assertThat(position.getLastPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(position.getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(position.getRealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(position.getM2m()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("handles non-numeric instrumentToken string")
        void handlesNonNumericInstrumentToken() {
            com.zerodhatech.models.Position kitePos = new com.zerodhatech.models.Position();
            kitePos.instrumentToken = "not-a-number";
            kitePos.tradingSymbol = "TEST";
            kitePos.averagePrice = 0.0;

            Position position = kitePositionMapper.toDomain(kitePos);

            assertThat(position.getInstrumentToken()).isNull();
        }

        @Test
        @DisplayName("null or empty instrumentToken returns null token")
        void handlesEmptyInstrumentToken() {
            com.zerodhatech.models.Position kitePos = new com.zerodhatech.models.Position();
            kitePos.instrumentToken = "";
            kitePos.averagePrice = 0.0;

            Position position = kitePositionMapper.toDomain(kitePos);
            assertThat(position.getInstrumentToken()).isNull();
        }
    }

    @Nested
    @DisplayName("List and Map Conversion")
    class ListMapTests {

        @Test
        @DisplayName("converts list of positions")
        void convertsList() {
            com.zerodhatech.models.Position p1 = new com.zerodhatech.models.Position();
            p1.instrumentToken = "100";
            p1.tradingSymbol = "CE";
            p1.netQuantity = 50;
            p1.averagePrice = 100.0;

            com.zerodhatech.models.Position p2 = new com.zerodhatech.models.Position();
            p2.instrumentToken = "200";
            p2.tradingSymbol = "PE";
            p2.netQuantity = -50;
            p2.averagePrice = 80.0;

            List<Position> positions = kitePositionMapper.toDomainList(List.of(p1, p2));

            assertThat(positions).hasSize(2);
            assertThat(positions.get(0).getType()).isEqualTo(PositionType.LONG);
            assertThat(positions.get(1).getType()).isEqualTo(PositionType.SHORT);
        }

        @Test
        @DisplayName("null list returns empty list")
        void nullListReturnsEmpty() {
            assertThat(kitePositionMapper.toDomainList(null)).isEmpty();
        }

        @Test
        @DisplayName("converts day+net positions map from Kite")
        void convertsPositionMap() {
            com.zerodhatech.models.Position dayPos = new com.zerodhatech.models.Position();
            dayPos.instrumentToken = "100";
            dayPos.tradingSymbol = "DAY";
            dayPos.netQuantity = 25;
            dayPos.averagePrice = 50.0;

            com.zerodhatech.models.Position netPos = new com.zerodhatech.models.Position();
            netPos.instrumentToken = "200";
            netPos.tradingSymbol = "NET";
            netPos.netQuantity = -50;
            netPos.averagePrice = 80.0;

            Map<String, List<com.zerodhatech.models.Position>> kiteMap =
                    Map.of("day", List.of(dayPos), "net", List.of(netPos));

            Map<String, List<Position>> result = kitePositionMapper.toDomainMap(kiteMap);

            assertThat(result.get("day")).hasSize(1);
            assertThat(result.get("day").get(0).getTradingSymbol()).isEqualTo("DAY");
            assertThat(result.get("net")).hasSize(1);
            assertThat(result.get("net").get(0).getTradingSymbol()).isEqualTo("NET");
        }

        @Test
        @DisplayName("null map returns empty day and net lists")
        void nullMapReturnsEmpty() {
            Map<String, List<Position>> result = kitePositionMapper.toDomainMap(null);

            assertThat(result.get("day")).isEmpty();
            assertThat(result.get("net")).isEmpty();
        }
    }
}
