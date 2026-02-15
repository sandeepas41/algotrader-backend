package com.algotrader.broker.mapper;

import com.algotrader.domain.model.Position;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps between Kite SDK position objects and our domain {@link Position} model.
 *
 * <p>Kite SDK Position uses public fields with mixed types: instrumentToken is a String,
 * averagePrice is a double (not Double), netQuantity is an int, and unrealised/realised
 * are Double (boxed). This mapper handles all type conversions and null safety.
 *
 * <p>Important: Kite's getPositions() returns a {@code Map<String, List<Position>>}
 * with keys "day" and "net". We merge both into a single flat list keyed by
 * "day" and "net" in the domain layer, since strategies typically only need net positions
 * but the UI shows both views.
 *
 * <p>Greeks are NOT populated here — Kite does not provide them. Greeks are calculated
 * separately by GreeksCalculator and attached to positions by PositionService.
 */
@Component
public class KitePositionMapper {

    /**
     * Converts a Kite SDK Position to our domain Position model.
     *
     * <p>Quantity uses Kite's netQuantity (signed: positive = long, negative = short).
     * The domain Position.getType() derives LONG/SHORT from the sign.
     */
    public Position toDomain(com.zerodhatech.models.Position kitePosition) {
        if (kitePosition == null) {
            return null;
        }

        // Broker positions don't have a persistent ID — derive one from exchange:symbol
        // so the FE can use it as a stable key for selection and rendering.
        String syntheticId = kitePosition.exchange + ":" + kitePosition.tradingSymbol;

        return Position.builder()
                .id(syntheticId)
                .instrumentToken(parseInstrumentToken(kitePosition.instrumentToken))
                .tradingSymbol(kitePosition.tradingSymbol)
                .exchange(kitePosition.exchange)
                .quantity(kitePosition.netQuantity)
                .averagePrice(BigDecimal.valueOf(kitePosition.averagePrice))
                .lastPrice(toNullableBigDecimal(kitePosition.lastPrice))
                .unrealizedPnl(toNullableBigDecimal(kitePosition.unrealised))
                .realizedPnl(toNullableBigDecimal(kitePosition.realised))
                .m2m(toNullableBigDecimal(kitePosition.m2m))
                .overnightQuantity(kitePosition.overnightQuantity)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * Converts a list of Kite SDK Positions to domain Positions.
     */
    public List<Position> toDomainList(List<com.zerodhatech.models.Position> kitePositions) {
        if (kitePositions == null) {
            return List.of();
        }
        return kitePositions.stream().map(this::toDomain).toList();
    }

    /**
     * Converts the Kite getPositions() response (Map with "day" and "net" keys)
     * to our domain format.
     */
    public Map<String, List<Position>> toDomainMap(
            Map<String, List<com.zerodhatech.models.Position>> kitePositionsMap) {
        if (kitePositionsMap == null) {
            return Map.of("day", List.of(), "net", List.of());
        }

        List<Position> dayPositions = toDomainList(kitePositionsMap.get("day"));
        List<Position> netPositions = toDomainList(kitePositionsMap.get("net"));

        return Map.of("day", dayPositions, "net", netPositions);
    }

    // ---- Helpers ----

    /**
     * Kite SDK Position.instrumentToken is a String (e.g., "256265").
     * Our domain uses Long.
     */
    private Long parseInstrumentToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Converts a boxed Double to BigDecimal, returning ZERO for null.
     */
    private BigDecimal toNullableBigDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }
}
