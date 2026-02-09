package com.algotrader.broker;

import com.algotrader.broker.mapper.KitePositionMapper;
import com.algotrader.domain.model.Position;
import com.algotrader.exception.BrokerException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Margin;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Internal service that fetches positions and margins from the Kite Connect API.
 *
 * <p>This is an implementation detail of {@link KiteBrokerGateway} — no other component
 * should inject this service directly. All external position queries must go through
 * {@link BrokerGateway}.
 *
 * <p>Kite's position data does NOT include Greeks. Greeks are calculated separately
 * by GreeksCalculator and attached to positions by PositionService.
 *
 * <p>Kite returns positions as a {@code Map<String, List<Position>>} with:
 * <ul>
 *   <li>"day" — intraday positions (opened today)</li>
 *   <li>"net" — net positions including overnight carry-forward</li>
 * </ul>
 *
 * <p>Margin fields in the Kite SDK are Strings (not doubles), parsed here to BigDecimal.
 */
@Service
public class KitePositionService {

    private static final Logger log = LoggerFactory.getLogger(KitePositionService.class);

    private final KiteConnect kiteConnect;
    private final KitePositionMapper kitePositionMapper;

    public KitePositionService(KiteConnect kiteConnect, KitePositionMapper kitePositionMapper) {
        this.kiteConnect = kiteConnect;
        this.kitePositionMapper = kitePositionMapper;
    }

    /**
     * Fetches all positions (day + net) from Kite.
     *
     * @return map with "day" and "net" position lists
     * @throws BrokerException if the API call fails
     */
    @RateLimiter(name = "kiteQuotes")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public Map<String, List<Position>> getPositions() {
        try {
            Map<String, List<com.zerodhatech.models.Position>> kitePositions = kiteConnect.getPositions();
            return kitePositionMapper.toDomainMap(kitePositions);
        } catch (KiteException e) {
            log.error("Failed to fetch positions: {}", e.message);
            throw new BrokerException("Failed to fetch positions: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Error fetching positions", e);
            throw new BrokerException("Error fetching positions: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches equity margin details from Kite.
     *
     * <p>Returns a flat map with keys: "cash", "collateral", "used", "available", "net".
     * All values are BigDecimal. Kite Margin fields are Strings, parsed with null safety.
     *
     * @return margin data as a name -> value map
     * @throws BrokerException if the API call fails
     */
    @RateLimiter(name = "kiteQuotes")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public Map<String, BigDecimal> getMargins() {
        try {
            // "equity" segment margins — this is what we use for F&O trading
            Margin margin = kiteConnect.getMargins("equity");
            return buildMarginMap(margin);
        } catch (KiteException e) {
            log.error("Failed to fetch margins: {}", e.message);
            throw new BrokerException("Failed to fetch margins: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Error fetching margins", e);
            throw new BrokerException("Error fetching margins: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts margin values from Kite's Margin object into a flat map.
     *
     * <p>Kite stores margin values as Strings in nested objects (Margin.Available, Margin.Utilised).
     * We parse each to BigDecimal with null safety.
     */
    private Map<String, BigDecimal> buildMarginMap(Margin margin) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (margin == null) {
            return result;
        }

        if (margin.available != null) {
            result.put("cash", parseBigDecimal(margin.available.cash));
            result.put("collateral", parseBigDecimal(margin.available.collateral));
            result.put("intradayPayin", parseBigDecimal(margin.available.intradayPayin));
            result.put("adhocMargin", parseBigDecimal(margin.available.adhocMargin));
            result.put("liveBalance", parseBigDecimal(margin.available.liveBalance));
        }

        if (margin.utilised != null) {
            result.put("used", parseBigDecimal(margin.utilised.debits));
            result.put("span", parseBigDecimal(margin.utilised.span));
            result.put("exposure", parseBigDecimal(margin.utilised.exposure));
            result.put("optionPremium", parseBigDecimal(margin.utilised.optionPremium));
        }

        result.put("net", parseBigDecimal(margin.net));

        return result;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
