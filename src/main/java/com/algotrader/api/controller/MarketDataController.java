package com.algotrader.api.controller;

import com.algotrader.api.dto.response.ChainExplorerResponse;
import com.algotrader.api.dto.response.InstrumentDumpEnvelope;
import com.algotrader.api.dto.response.InstrumentDumpResponse;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.OptionChain;
import com.algotrader.mapper.InstrumentDumpMapper;
import com.algotrader.service.InstrumentService;
import com.algotrader.service.OptionChainService;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *   <li>GET /api/market-data/chain -- chain explorer (FUT + option strikes) for underlying + expiry</li>
 *   <li>GET /api/market-data/instruments/dump -- bulk dump of all instruments for FE IndexedDB</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market-data")
public class MarketDataController {

    private final OptionChainService optionChainService;
    private final InstrumentService instrumentService;
    private final InstrumentDumpMapper instrumentDumpMapper;

    public MarketDataController(
            OptionChainService optionChainService,
            InstrumentService instrumentService,
            InstrumentDumpMapper instrumentDumpMapper) {
        this.optionChainService = optionChainService;
        this.instrumentService = instrumentService;
        this.instrumentDumpMapper = instrumentDumpMapper;
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
     * Returns the chain explorer data for a given underlying and expiry.
     * Includes spot token, nearest future, and all option strikes paired as call/put.
     */
    @GetMapping("/chain")
    public ResponseEntity<ChainExplorerResponse> getChainExplorer(
            @RequestParam String underlying,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry) {
        ChainExplorerResponse chain = instrumentService.buildChainExplorer(underlying, expiry);
        return ResponseEntity.ok(chain);
    }

    /**
     * Bulk dump of all cached instruments for FE IndexedDB population.
     *
     * <p>Serializes all ~68k instruments from the in-memory tokenCache with field names
     * mapped to FE conventions (token → instrumentToken, type → instrumentType, etc.).
     * The FE calls this once per trading day on first login to populate its local IndexedDB cache.
     *
     * <p>Response is ~14 MB uncompressed, ~2-3 MB gzipped (server compression enabled).
     */
    @GetMapping("/instruments/dump")
    public ResponseEntity<InstrumentDumpEnvelope> getInstrumentDump() {
        List<InstrumentDumpResponse> responses =
                instrumentDumpMapper.toResponseList(new ArrayList<>(instrumentService.getAllCachedInstruments()));

        LocalDate downloadDate = instrumentService.getDownloadDate();

        InstrumentDumpEnvelope envelope = InstrumentDumpEnvelope.builder()
                .instruments(responses)
                .downloadDate(downloadDate != null ? downloadDate.toString() : null)
                .build();

        return ResponseEntity.ok(envelope);
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
