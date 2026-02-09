package com.algotrader.api.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the current tick recording session's live statistics.
 *
 * <p>Returned by GET /api/recordings/stats. The frontend's simulator page
 * polls this endpoint to show real-time recording status and tick counts.
 */
@Getter
@Builder
public class RecordingStatsResponse {

    /** Whether the recorder is currently active. */
    private final boolean recording;

    /** The trading date being recorded. */
    private final LocalDate date;

    /** Number of ticks currently buffered in memory awaiting flush. */
    private final long ticksBuffered;

    /** Number of ticks already flushed to disk. */
    private final long ticksFlushed;

    /** Total ticks recorded in this session (buffered + flushed). */
    private final long totalTicks;

    /** Current file size on disk in bytes. */
    private final long fileSizeBytes;

    /** When the current recording session started. Null if not recording. */
    private final LocalDateTime startTime;
}
