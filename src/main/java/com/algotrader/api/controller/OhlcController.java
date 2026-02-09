package com.algotrader.api.controller;

import com.algotrader.api.dto.response.CandleResponse;
import com.algotrader.mapper.CandleMapper;
import com.algotrader.timeseries.Candle;
import com.algotrader.timeseries.CandleInterval;
import com.algotrader.timeseries.OhlcService;
import java.util.List;
import org.mapstruct.factory.Mappers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for querying OHLCV candle data from Redis TimeSeries.
 *
 * <p>Candles are computed on-demand via server-side aggregation in Redis TimeSeries.
 * The frontend calls this endpoint to populate candlestick charts, with the time range
 * and interval controlled by chart zoom/pan interactions.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>GET /api/market-data/candles?token={}&interval={}&from={}&to={}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market-data")
public class OhlcController {

    private final OhlcService ohlcService;
    private final CandleMapper candleMapper = Mappers.getMapper(CandleMapper.class);

    public OhlcController(OhlcService ohlcService) {
        this.ohlcService = ohlcService;
    }

    /**
     * Query OHLCV candles for an instrument over a time range.
     *
     * @param token    Kite instrument token
     * @param interval candle interval suffix: "1m", "5m", "15m", or "1h"
     * @param from     range start in epoch milliseconds
     * @param to       range end in epoch milliseconds
     * @return list of candles ordered by timestamp ascending
     */
    @GetMapping("/candles")
    public ResponseEntity<List<CandleResponse>> getCandles(
            @RequestParam long token, @RequestParam String interval, @RequestParam long from, @RequestParam long to) {

        CandleInterval candleInterval = CandleInterval.fromSuffix(interval);
        List<Candle> candles = ohlcService.getCandles(token, candleInterval, from, to);
        List<CandleResponse> response = candleMapper.toResponseList(candles);

        return ResponseEntity.ok(response);
    }
}
