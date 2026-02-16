package com.algotrader.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.repository.jpa.StrategyJpaRepository;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.impl.StraddleConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cross-service integration test for strategy lifecycle management.
 * Wires real StrategyEngine + StrategyFactory with mocked external dependencies
 * to verify the complete lifecycle: deploy -> arm -> pause -> resume -> close.
 */
@ExtendWith(MockitoExtension.class)
class StrategyLifecycleIntegrationTest {

    private StrategyEngine strategyEngine;

    @BeforeEach
    void setUp() {
        EventPublisherHelper eventPublisherHelper = mock(EventPublisherHelper.class);
        JournaledMultiLegExecutor journaledMultiLegExecutor = mock(JournaledMultiLegExecutor.class);
        InstrumentService instrumentService = mock(InstrumentService.class);

        StrategyFactory strategyFactory = new StrategyFactory();
        StrategyJpaRepository strategyJpaRepository = mock(StrategyJpaRepository.class);
        strategyEngine = new StrategyEngine(
                strategyFactory,
                eventPublisherHelper,
                journaledMultiLegExecutor,
                instrumentService,
                strategyJpaRepository);
    }

    @Test
    @DisplayName("Full lifecycle: deploy -> arm -> pause -> resume -> close")
    void fullLifecycle() {
        StraddleConfig config = buildConfig("NIFTY");

        // Deploy
        String strategyId = strategyEngine.deployStrategy(StrategyType.STRADDLE, "Test Straddle", config, false);
        assertThat(strategyId).startsWith("STR-");

        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);

        // Arm
        strategyEngine.armStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);

        // Pause
        strategyEngine.pauseStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);

        // Resume
        strategyEngine.resumeStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);

        // Close
        strategyEngine.closeStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
    }

    @Test
    @DisplayName("Deploy with autoArm creates strategy in ARMED state")
    void deployWithAutoArm() {
        StraddleConfig config = buildConfig("NIFTY");

        String strategyId = strategyEngine.deployStrategy(StrategyType.STRADDLE, "Auto-Armed", config, true);
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
    }

    @Test
    @DisplayName("Cannot undeploy non-CLOSED strategy")
    void cannotUndeployActiveStrategy() {
        StraddleConfig config = buildConfig("NIFTY");

        String strategyId = strategyEngine.deployStrategy(StrategyType.STRADDLE, "Active", config, true);

        assertThatThrownBy(() -> strategyEngine.undeployStrategy(strategyId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("pauseAll pauses all deployed strategies")
    void pauseAllPausesDeployed() {
        StraddleConfig config1 = buildConfig("NIFTY");
        StraddleConfig config2 = buildConfig("BANKNIFTY");

        String id1 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "S1", config1, true);
        String id2 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "S2", config2, true);

        strategyEngine.pauseAll();

        assertThat(strategyEngine.getStrategy(id1).getStatus()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(strategyEngine.getStrategy(id2).getStatus()).isEqualTo(StrategyStatus.PAUSED);
    }

    @Test
    @DisplayName("Multiple strategy types can be deployed concurrently")
    void multipleStrategyTypes() {
        StraddleConfig straddleConfig = buildConfig("NIFTY");

        String id1 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "Straddle", straddleConfig, false);
        String id2 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "Straddle 2", straddleConfig, false);

        assertThat(strategyEngine.getActiveStrategies()).hasSize(2);
        assertThat(id1).isNotEqualTo(id2);
    }

    private StraddleConfig buildConfig(String underlying) {
        return StraddleConfig.builder()
                .underlying(underlying)
                .lots(1)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();
    }
}
