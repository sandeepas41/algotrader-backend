package com.algotrader.simulator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

/**
 * Represents an active or completed tick replay session.
 *
 * <p>Tracks playback state (speed, progress, status) for both internal control
 * and frontend reporting via ReplayProgressEvent. A session is created when replay
 * starts and finalized when it completes, is stopped, or encounters an error.
 *
 * <p>The sessionId is a UUID generated at session start and used to correlate
 * all events and decision logs produced during this replay.
 */
@Data
@Builder
public class PlaybackSession {

    private String sessionId;
    private LocalDate date;

    @Builder.Default
    private double speed = 1.0;

    private PlaybackStatus status;

    /** Number of ticks processed so far. */
    private int ticksProcessed;

    /** Total number of ticks in the recording. */
    private int totalTicks;

    /** Optional time range filter: start time. Null means replay from file start. */
    private LocalTime startTime;

    /** Optional time range filter: end time. Null means replay to file end. */
    private LocalTime endTime;

    /** Optional instrument filter. Null or empty means replay all instruments. */
    private Set<Long> instrumentTokens;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    /**
     * Playback lifecycle states.
     */
    public enum PlaybackStatus {
        RUNNING,
        PAUSED,
        COMPLETED,
        STOPPED,
        FAILED
    }
}
