package com.algotrader.strategy;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.impl.BearCallSpreadConfig;
import com.algotrader.strategy.impl.BearCallSpreadStrategy;
import com.algotrader.strategy.impl.BearPutSpreadConfig;
import com.algotrader.strategy.impl.BearPutSpreadStrategy;
import com.algotrader.strategy.impl.BullCallSpreadConfig;
import com.algotrader.strategy.impl.BullCallSpreadStrategy;
import com.algotrader.strategy.impl.BullPutSpreadConfig;
import com.algotrader.strategy.impl.BullPutSpreadStrategy;
import com.algotrader.strategy.impl.CalendarSpreadConfig;
import com.algotrader.strategy.impl.CalendarSpreadStrategy;
import com.algotrader.strategy.impl.DiagonalSpreadConfig;
import com.algotrader.strategy.impl.DiagonalSpreadStrategy;
import com.algotrader.strategy.impl.IronButterflyConfig;
import com.algotrader.strategy.impl.IronButterflyStrategy;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.IronCondorStrategy;
import com.algotrader.strategy.impl.ScalpingConfig;
import com.algotrader.strategy.impl.ScalpingStrategy;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StraddleStrategy;
import com.algotrader.strategy.impl.StrangleConfig;
import com.algotrader.strategy.impl.StrangleStrategy;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates strategy instances from type + config. Maps StrategyType to the concrete
 * strategy class and initializes it with an ID and config.
 *
 * <p><b>Adding a new strategy type (4-step process):</b>
 * <ol>
 *   <li>Create the strategy class extending BaseStrategy (e.g., {@code StraddleStrategy.java})</li>
 *   <li>Create the config class extending BaseStrategyConfig or PositionalStrategyConfig</li>
 *   <li>Add a case in {@link #create(StrategyType, String, BaseStrategyConfig)} for the new type</li>
 *   <li>Add the type to {@link StrategyType} enum if not already present</li>
 * </ol>
 *
 * <p>Strategy instances are plain Java objects, not Spring beans. The StrategyEngine
 * injects runtime services via {@link com.algotrader.strategy.base.StrategyContext}
 * after creation.
 */
@Component
public class StrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(StrategyFactory.class);

    /**
     * Creates a strategy instance for the given type with generated ID.
     *
     * @param type   the strategy type to create
     * @param name   user-provided name for this instance (e.g., "NIFTY-Straddle-Morning")
     * @param config the strategy-specific configuration
     * @return a new BaseStrategy instance in CREATED state
     * @throws UnsupportedOperationException if the strategy type is not yet implemented
     */
    public BaseStrategy create(StrategyType type, String name, BaseStrategyConfig config) {
        String id = generateId();
        BaseStrategy strategy = instantiate(type, id, name, config);
        log.info("Created strategy: id={}, type={}, name={}", id, type, name);
        return strategy;
    }

    private BaseStrategy instantiate(StrategyType type, String id, String name, BaseStrategyConfig config) {
        return switch (type) {
            case STRADDLE -> new StraddleStrategy(id, name, asConfig(config, StraddleConfig.class));
            case STRANGLE -> new StrangleStrategy(id, name, asConfig(config, StrangleConfig.class));
            case IRON_CONDOR -> new IronCondorStrategy(id, name, asConfig(config, IronCondorConfig.class));
            case IRON_BUTTERFLY -> new IronButterflyStrategy(id, name, asConfig(config, IronButterflyConfig.class));
            case BULL_CALL_SPREAD -> new BullCallSpreadStrategy(id, name, asConfig(config, BullCallSpreadConfig.class));
            case BEAR_CALL_SPREAD -> new BearCallSpreadStrategy(id, name, asConfig(config, BearCallSpreadConfig.class));
            case BULL_PUT_SPREAD -> new BullPutSpreadStrategy(id, name, asConfig(config, BullPutSpreadConfig.class));
            case BEAR_PUT_SPREAD -> new BearPutSpreadStrategy(id, name, asConfig(config, BearPutSpreadConfig.class));
            case CALENDAR_SPREAD -> new CalendarSpreadStrategy(id, name, asConfig(config, CalendarSpreadConfig.class));
            case DIAGONAL_SPREAD -> new DiagonalSpreadStrategy(id, name, asConfig(config, DiagonalSpreadConfig.class));
            case SCALPING -> new ScalpingStrategy(id, name, asConfig(config, ScalpingConfig.class));
            case CUSTOM ->
                throw new UnsupportedOperationException(
                        "CUSTOM strategy type requires user-defined configuration. See PLAN.md for implementation.");
        };
    }

    /**
     * Safely casts the base config to the expected strategy-specific config type.
     * Fails fast with a clear message if the wrong config type is passed.
     */
    private <T extends BaseStrategyConfig> T asConfig(BaseStrategyConfig config, Class<T> expectedType) {
        if (!expectedType.isInstance(config)) {
            throw new IllegalArgumentException("Expected config type " + expectedType.getSimpleName() + " but got "
                    + config.getClass().getSimpleName());
        }
        return expectedType.cast(config);
    }

    /**
     * Generates a unique strategy ID with STR- prefix and 8-char UUID suffix.
     * Format: STR-A1B2C3D4
     */
    String generateId() {
        return "STR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
