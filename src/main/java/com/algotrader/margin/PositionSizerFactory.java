package com.algotrader.margin;

import com.algotrader.domain.enums.PositionSizingType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link PositionSizer} implementation by {@link PositionSizingType}.
 *
 * <p>Spring auto-discovers all PositionSizer beans and this factory indexes them
 * by type at construction time. Strategies call this factory with their configured
 * sizing type to get the appropriate sizer.
 *
 * <p>If no sizer is registered for a requested type, an {@link IllegalArgumentException}
 * is thrown — this indicates a configuration error (missing @Component annotation
 * on the sizer implementation).
 */
@Component
public class PositionSizerFactory {

    private final Map<PositionSizingType, PositionSizer> sizersByType;

    public PositionSizerFactory(List<PositionSizer> positionSizers) {
        this.sizersByType =
                positionSizers.stream().collect(Collectors.toMap(PositionSizer::getType, Function.identity()));
    }

    /**
     * Returns the sizer for the given type.
     *
     * @param positionSizingType the sizing strategy
     * @return the matching PositionSizer implementation
     * @throws IllegalArgumentException if no sizer is registered for the type
     */
    public PositionSizer getSizer(PositionSizingType positionSizingType) {
        PositionSizer positionSizer = sizersByType.get(positionSizingType);
        if (positionSizer == null) {
            throw new IllegalArgumentException("No position sizer found for type: " + positionSizingType);
        }
        return positionSizer;
    }
}
