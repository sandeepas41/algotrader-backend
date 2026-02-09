package com.algotrader.domain.model;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * Metadata about a tick recording session (one per trading day).
 *
 * <p>Used by TickRecordingController to list available recordings
 * and their basic properties (date, file size, tick count).
 */
@Data
@Builder
public class RecordingInfo {

    /** The trading date this recording covers. */
    private LocalDate date;

    /** File size in bytes. */
    private long fileSizeBytes;

    /** Number of ticks recorded. */
    private int tickCount;

    /** Whether the file is compressed (.gz). */
    private boolean compressed;

    /** File path relative to the recording directory. */
    private String filePath;
}
