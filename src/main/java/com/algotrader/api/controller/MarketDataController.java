package com.algotrader.api.controller;

import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.OptionChain;
import com.algotrader.service.InstrumentService;
import com.algotrader.service.OptionChainService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for market data: option chains, instruments, expiries, and quotes.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/market-data/option-chain -- option chain for underlying + expiry</li>
 *   <li>GET /api/market-data/instruments -- search instruments by symbol prefix</li>
 *   <li>GET /api/market-data/instruments/{token} -- instrument by token</li>
 *   <li>GET /api/market-data/expiries/{underlying} -- available expiry dates</li>
 *   <li>GET /api/market-data/underlyings -- available underlying symbols</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

    private final OptionChainService optionChainService;
    private final InstrumentService instrumentService;

    public MarketDataController(OptionChainService optionChainService, InstrumentService instrumentService) {
        this.optionChainService = optionChainService;
        this.instrumentService = instrumentService;
    }

    /**
     * Returns the option chain for a given underlying and expiry date.
     */
    @GetMapping("/option-chain")
    public ResponseEntity<OptionChain> getOptionChain(
            @RequestParam String underlying,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry) {
        OptionChain chain = optionChainService.getOptionChain(underlying, expiry);
        return ResponseEntity.ok(chain);
    }

    /**
     * Search instruments by query (matches tradingSymbol prefix, name contains, underlying prefix).
     * Optional exchange filter (e.g., "NSE", "NFO", "BSE").
     */
    @GetMapping("/instruments")
    public ResponseEntity<List<Instrument>> searchInstruments(
            @RequestParam String query, @RequestParam(required = false) String exchange) {
        List<Instrument> instruments = instrumentService.searchInstruments(query, exchange);
        return ResponseEntity.ok(instruments);
    }

    /**
     * Returns a single instrument by its token.
     */
    @GetMapping("/instruments/{token}")
    public ResponseEntity<Instrument> getInstrument(@PathVariable long token) {
        return instrumentService
                .findByToken(token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns available expiry dates for a given underlying (e.g., NIFTY).
     */
    @GetMapping("/expiries/{underlying}")
    public ResponseEntity<List<LocalDate>> getExpiries(@PathVariable String underlying) {
        List<LocalDate> expiries = instrumentService.getExpiries(underlying);
        return ResponseEntity.ok(expiries);
    }

    /**
     * Returns all available underlying symbols (e.g., NIFTY, BANKNIFTY, FINNIFTY).
     */
    @GetMapping("/underlyings")
    public ResponseEntity<Set<String>> getUnderlyings() {
        Set<String> underlyings = instrumentService.getAvailableUnderlyings();
        return ResponseEntity.ok(underlyings);
    }

    /**
     * Returns instruments for a specific underlying (e.g., all NIFTY options/futures).
     */
    @GetMapping("/instruments/underlying/{underlying}")
    public ResponseEntity<List<Instrument>> getInstrumentsByUnderlying(@PathVariable String underlying) {
        List<Instrument> instruments = instrumentService.getInstrumentsByUnderlying(underlying);
        return ResponseEntity.ok(instruments);
    }
}
