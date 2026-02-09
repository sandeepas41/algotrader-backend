package com.algotrader.unit.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.event.RiskEvent;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.redis.OrderRedisRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.RiskLimits;
import com.algotrader.risk.RiskViolation;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for AccountRiskChecker covering daily loss tracking (thread-safe AtomicReference),
 * max position/order limits, and account-level risk event publishing.
 */
@ExtendWith(MockitoExtension.class)
class AccountRiskCheckerTest {

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private OrderRedisRepository orderRedisRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private RiskLimits riskLimits;
    private AccountRiskChecker accountRiskChecker;

    @BeforeEach
    void setUp() {
        riskLimits = RiskLimits.builder()
                .dailyLossLimit(new BigDecimal("50000"))
                .dailyLossWarningThreshold(new BigDecimal("0.8"))
                .maxOpenPositions(10)
                .maxOpenOrders(20)
                .build();
        accountRiskChecker = new AccountRiskChecker(
                riskLimits, positionRedisRepository, orderRedisRepository, applicationEventPublisher);
    }

    // ==============================
    // DAILY LOSS TRACKING
    // ==============================

    @Nested
    @DisplayName("Daily Loss Tracking")
    class DailyLossTracking {

        @Test
        @DisplayName("Initial daily P&L is zero")
        void initialPnl_isZero() {
            assertThat(accountRiskChecker.getDailyRealisedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Recording realized P&L updates the daily total")
        void recordPnl_updatesDailyTotal() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-5000"));
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-3000"));

            assertThat(accountRiskChecker.getDailyRealisedPnl()).isEqualByComparingTo("-8000");
        }

        @Test
        @DisplayName("Recording positive P&L adds to total")
        void recordPositivePnl_addedToTotal() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-10000"));
            accountRiskChecker.recordRealisedPnl(new BigDecimal("3000"));

            assertThat(accountRiskChecker.getDailyRealisedPnl()).isEqualByComparingTo("-7000");
        }

        @Test
        @DisplayName("resetDailyPnl sets a specific value")
        void resetDailyPnl_setsValue() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-20000"));
            accountRiskChecker.resetDailyPnl(new BigDecimal("-5000"));

            assertThat(accountRiskChecker.getDailyRealisedPnl()).isEqualByComparingTo("-5000");
        }

        @Test
        @DisplayName("Thread-safe concurrent P&L recording with AtomicReference")
        void concurrentPnlRecording_threadSafe() throws InterruptedException {
            int threadCount = 10;
            int operationsPerThread = 100;
            BigDecimal pnlPerOperation = new BigDecimal("-10");

            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    for (int j = 0; j < operationsPerThread; j++) {
                        accountRiskChecker.recordRealisedPnl(pnlPerOperation);
                    }
                    latch.countDown();
                });
            }

            latch.await();
            executorService.shutdown();

            // Total should be threadCount * operationsPerThread * pnlPerOperation
            BigDecimal expected =
                    pnlPerOperation.multiply(BigDecimal.valueOf((long) threadCount * operationsPerThread));
            assertThat(accountRiskChecker.getDailyRealisedPnl()).isEqualByComparingTo(expected);
        }
    }

    // ==============================
    // DAILY LOSS LIMIT CHECKS
    // ==============================

    @Nested
    @DisplayName("Daily Loss Limit Checks")
    class DailyLossLimitChecks {

        @Test
        @DisplayName("Daily loss within limit is not breached")
        void withinLimit_notBreached() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-30000"));

            assertThat(accountRiskChecker.isDailyLimitBreached()).isFalse();
        }

        @Test
        @DisplayName("Daily loss exactly at limit is breached")
        void exactlyAtLimit_breached() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-50000"));

            assertThat(accountRiskChecker.isDailyLimitBreached()).isTrue();
        }

        @Test
        @DisplayName("Daily loss exceeding limit is breached")
        void exceedingLimit_breached() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-60000"));

            assertThat(accountRiskChecker.isDailyLimitBreached()).isTrue();
        }

        @Test
        @DisplayName("Null daily loss limit disables the check")
        void nullDailyLossLimit_checkDisabled() {
            riskLimits.setDailyLossLimit(null);
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-999999"));

            assertThat(accountRiskChecker.isDailyLimitBreached()).isFalse();
        }

        @Test
        @DisplayName("Daily loss approaching warning threshold is detected")
        void approachingWarning_detected() {
            // Warning at 80% of 50000 = 40000
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-42000"));

            assertThat(accountRiskChecker.isDailyLimitApproaching()).isTrue();
        }

        @Test
        @DisplayName("Daily loss below warning threshold is not approaching")
        void belowWarning_notApproaching() {
            // Warning at 80% of 50000 = 40000
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-30000"));

            assertThat(accountRiskChecker.isDailyLimitApproaching()).isFalse();
        }

        @Test
        @DisplayName("Daily loss at breach level is not 'approaching' (it's past that)")
        void atBreach_notApproaching() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-50000"));

            // It's breached, not just approaching
            assertThat(accountRiskChecker.isDailyLimitApproaching()).isFalse();
            assertThat(accountRiskChecker.isDailyLimitBreached()).isTrue();
        }
    }

    // ==============================
    // PRE-TRADE VALIDATION
    // ==============================

    @Nested
    @DisplayName("Pre-trade Validation")
    class PreTradeValidation {

        @Test
        @DisplayName("Order passes when all account limits are within range")
        void allWithinLimits_passes() {
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());
            when(orderRedisRepository.countPending()).thenReturn(0);

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Order rejected when daily loss limit is breached")
        void dailyLossBreached_rejected() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-55000"));

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).anyMatch(v -> v.getCode().equals("DAILY_LOSS_LIMIT_BREACHED"));
        }

        @Test
        @DisplayName("Order rejected when max positions reached")
        void maxPositionsReached_rejected() {
            when(positionRedisRepository.findAll()).thenReturn(Collections.nCopies(10, null));

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).anyMatch(v -> v.getCode().equals("MAX_POSITIONS_REACHED"));
        }

        @Test
        @DisplayName("Order rejected when max orders reached")
        void maxOrdersReached_rejected() {
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());
            when(orderRedisRepository.countPending()).thenReturn(20);

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).anyMatch(v -> v.getCode().equals("MAX_ORDERS_REACHED"));
        }

        @Test
        @DisplayName("Multiple account violations returned together")
        void multipleViolations_allReturned() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-55000"));
            when(positionRedisRepository.findAll()).thenReturn(Collections.nCopies(10, null));
            when(orderRedisRepository.countPending()).thenReturn(20);

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).hasSize(3);
        }

        @Test
        @DisplayName("Null limits disable all account checks")
        void nullLimits_allDisabled() {
            riskLimits.setDailyLossLimit(null);
            riskLimits.setMaxOpenPositions(null);
            riskLimits.setMaxOpenOrders(null);

            accountRiskChecker.recordRealisedPnl(new BigDecimal("-999999"));

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = accountRiskChecker.validateOrder(request);

            assertThat(violations).isEmpty();
        }
    }

    // ==============================
    // ACCOUNT LIMIT MONITORING
    // ==============================

    @Nested
    @DisplayName("Account Limit Monitoring")
    class AccountLimitMonitoring {

        @Test
        @DisplayName("checkAccountLimits publishes CRITICAL event on breach")
        void breach_publishesCritical() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-55000"));

            accountRiskChecker.checkAccountLimits();

            verify(applicationEventPublisher).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("checkAccountLimits publishes WARNING event when approaching")
        void approaching_publishesWarning() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-42000"));

            accountRiskChecker.checkAccountLimits();

            verify(applicationEventPublisher).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("checkAccountLimits does not publish when within safe range")
        void withinSafe_noEvent() {
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-20000"));

            accountRiskChecker.checkAccountLimits();

            verify(applicationEventPublisher, never()).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("checkAccountLimits with null daily loss limit does nothing")
        void nullLimit_noEvent() {
            riskLimits.setDailyLossLimit(null);
            accountRiskChecker.recordRealisedPnl(new BigDecimal("-999999"));

            accountRiskChecker.checkAccountLimits();

            verify(applicationEventPublisher, never()).publishEvent(any(RiskEvent.class));
        }
    }
}
