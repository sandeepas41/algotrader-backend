package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KitePositionService;
import com.algotrader.broker.mapper.KitePositionMapper;
import com.algotrader.domain.model.Position;
import com.algotrader.exception.BrokerException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Margin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KitePositionService}.
 *
 * <p>Tests position fetching with day/net mapping, margin parsing from Kite's
 * String-typed fields, and error handling. KiteConnect is mocked; KitePositionMapper
 * uses a real instance since it's a pure function mapper.
 */
@ExtendWith(MockitoExtension.class)
class KitePositionServiceTest {

    @Mock
    private KiteConnect kiteConnect;

    private KitePositionMapper kitePositionMapper;
    private KitePositionService kitePositionService;

    @BeforeEach
    void setUp() {
        kitePositionMapper = new KitePositionMapper();
        kitePositionService = new KitePositionService(kiteConnect, kitePositionMapper);
    }

    @Nested
    @DisplayName("Get Positions")
    class GetPositionsTests {

        @Test
        @DisplayName("fetches and maps day + net positions")
        void fetchesDayAndNetPositions() throws Throwable {
            com.zerodhatech.models.Position dayPos = new com.zerodhatech.models.Position();
            dayPos.instrumentToken = "256265";
            dayPos.tradingSymbol = "NIFTY24FEB22000CE";
            dayPos.exchange = "NFO";
            dayPos.netQuantity = 50;
            dayPos.averagePrice = 150.0;

            com.zerodhatech.models.Position netPos = new com.zerodhatech.models.Position();
            netPos.instrumentToken = "256266";
            netPos.tradingSymbol = "NIFTY24FEB22000PE";
            netPos.exchange = "NFO";
            netPos.netQuantity = -75;
            netPos.averagePrice = 80.0;

            Map<String, List<com.zerodhatech.models.Position>> kitePositions =
                    Map.of("day", List.of(dayPos), "net", List.of(netPos));

            when(kiteConnect.getPositions()).thenReturn(kitePositions);

            Map<String, List<Position>> result = kitePositionService.getPositions();

            assertThat(result.get("day")).hasSize(1);
            assertThat(result.get("day").get(0).getQuantity()).isEqualTo(50);
            assertThat(result.get("net")).hasSize(1);
            assertThat(result.get("net").get(0).getQuantity()).isEqualTo(-75);
        }

        @Test
        @DisplayName("wraps KiteException as BrokerException")
        void wrapsKiteException() throws Throwable {
            when(kiteConnect.getPositions()).thenThrow(new KiteException("Token expired", 403));

            assertThatThrownBy(() -> kitePositionService.getPositions())
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Token expired");
        }
    }

    @Nested
    @DisplayName("Get Margins")
    class GetMarginsTests {

        @Test
        @DisplayName("fetches and parses equity margin fields")
        void fetchesEquityMargins() throws Throwable {
            Margin margin = new Margin();
            margin.available = new Margin.Available();
            margin.available.cash = "500000.50";
            margin.available.collateral = "200000.00";
            margin.available.intradayPayin = "0";
            margin.available.adhocMargin = "0";
            margin.available.liveBalance = "700000.50";
            margin.utilised = new Margin.Utilised();
            margin.utilised.debits = "150000.25";
            margin.utilised.span = "100000.00";
            margin.utilised.exposure = "30000.00";
            margin.utilised.optionPremium = "20000.25";
            margin.net = "550000.25";

            when(kiteConnect.getMargins(eq("equity"))).thenReturn(margin);

            Map<String, BigDecimal> result = kitePositionService.getMargins();

            assertThat(result.get("cash")).isEqualByComparingTo(new BigDecimal("500000.50"));
            assertThat(result.get("collateral")).isEqualByComparingTo(new BigDecimal("200000.00"));
            assertThat(result.get("used")).isEqualByComparingTo(new BigDecimal("150000.25"));
            assertThat(result.get("span")).isEqualByComparingTo(new BigDecimal("100000.00"));
            assertThat(result.get("exposure")).isEqualByComparingTo(new BigDecimal("30000.00"));
            assertThat(result.get("optionPremium")).isEqualByComparingTo(new BigDecimal("20000.25"));
            assertThat(result.get("net")).isEqualByComparingTo(new BigDecimal("550000.25"));
        }

        @Test
        @DisplayName("handles null available/utilised gracefully")
        void handlesNullMarginSections() throws Throwable {
            Margin margin = new Margin();
            margin.available = null;
            margin.utilised = null;
            margin.net = "100000";

            when(kiteConnect.getMargins(eq("equity"))).thenReturn(margin);

            Map<String, BigDecimal> result = kitePositionService.getMargins();

            // Only "net" should be present when available/utilised are null
            assertThat(result.get("net")).isEqualByComparingTo(new BigDecimal("100000"));
            assertThat(result).doesNotContainKey("cash");
        }

        @Test
        @DisplayName("handles null margin entirely")
        void handlesNullMargin() throws Throwable {
            when(kiteConnect.getMargins(eq("equity"))).thenReturn(null);

            Map<String, BigDecimal> result = kitePositionService.getMargins();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("handles non-numeric margin strings as zero")
        void handlesNonNumericMarginStrings() throws Throwable {
            Margin margin = new Margin();
            margin.available = new Margin.Available();
            margin.available.cash = "not-a-number";
            margin.available.collateral = null;
            margin.net = "";

            when(kiteConnect.getMargins(eq("equity"))).thenReturn(margin);

            Map<String, BigDecimal> result = kitePositionService.getMargins();

            assertThat(result.get("cash")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.get("net")).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("wraps KiteException as BrokerException")
        void wrapsKiteException() throws Throwable {
            when(kiteConnect.getMargins(eq("equity"))).thenThrow(new KiteException("API error", 500));

            assertThatThrownBy(() -> kitePositionService.getMargins())
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("API error");
        }
    }
}
