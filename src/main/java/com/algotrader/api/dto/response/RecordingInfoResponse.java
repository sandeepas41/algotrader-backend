package com.algotrader.api.dto.response;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for a single tick recording file's metadata.
 *
 * <p>Returned as part of the recordings list from GET /api/recordings.
 * Shows the date, file size, tick count, and compression status.
 */
@Getter
@Builder
public class RecordingInfoResponse {

    /** The trading date this recording covers. */
    private final LocalDate date;

    /** File size in bytes. */
    private final long fileSizeBytes;

    /** Number of ticks recorded. */
    private final int tickCount;

    /** Whether the file is compressed (.gz). */
    private final boolean compressed;

    /** File name relative to the recording directory. */
    private final String filePath;
}
