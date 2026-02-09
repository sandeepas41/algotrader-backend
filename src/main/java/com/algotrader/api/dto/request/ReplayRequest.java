package com.algotrader.api.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import lombok.Data;

/**
 * Request payload for starting a tick replay session.
 *
 * <p>The date is required (identifies which recording to replay). Speed defaults to 1.0x.
 * startTime, endTime, and instrumentTokens are optional filters for selective replay.
 */
@Data
public class ReplayRequest {

    /** Date of the recording to replay (required). */
    private LocalDate date;

    /** Playback speed multiplier. Range: 0.5 to 10.0. Default: 1.0. */
    private double speed = 1.0;

    /** Optional start time filter. Null means start from the beginning. */
    private LocalTime startTime;

    /** Optional end time filter. Null means replay to the end. */
    private LocalTime endTime;

    /** Optional instrument token filter. Null or empty means replay all instruments. */
    private Set<Long> instrumentTokens;
}
