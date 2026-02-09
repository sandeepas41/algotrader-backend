package com.algotrader.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Live statistics for the current tick recording session.
 *
 * <p>Tracks the number of ticks buffered in memory, ticks flushed to disk,
 * file size on disk, and session timing. Used by the TickRecordingController
 * to expose real-time recording status to the frontend.
 */
@Data
@Builder
public class RecordingStats {

    /** Whether the recorder is currently active. */
    private boolean recording;

    /** The trading date being recorded. */
    private LocalDate date;

    /** Number of ticks currently buffered in memory awaiting flush. */
    private long ticksBuffered;

    /** Number of ticks already flushed to disk. */
    private long ticksFlushed;

    /** Total ticks recorded in this session (buffered + flushed). */
    private long totalTicks;

    /** Current file size on disk in bytes. */
    private long fileSizeBytes;

    /** When the current recording session started. Null if not recording. */
    private LocalDateTime startTime;
}
