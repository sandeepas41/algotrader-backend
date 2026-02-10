package com.algotrader.api.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Wrapper for the bulk instrument dump, containing the full instruments array
 * plus the download date for freshness checking.
 *
 * <p>Uses {@code instruments} (not {@code data}) as the field name to avoid
 * confusing nesting with {@link ApiResponse}'s outer {@code data} wrapper.
 * The FE checks {@code downloadDate} against today's date to decide whether
 * IndexedDB needs a refresh.
 */
@Getter
@Builder
public class InstrumentDumpEnvelope {

    /** All instruments from the BE in-memory cache. */
    private final List<InstrumentDumpResponse> instruments;

    /** ISO date string (e.g., "2026-02-10") when instruments were downloaded from Kite. */
    private final String downloadDate;
}
