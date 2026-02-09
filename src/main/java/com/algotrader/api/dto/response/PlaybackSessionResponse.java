package com.algotrader.api.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for a tick replay session status.
 *
 * <p>Returned by the replay start, status, and control endpoints to convey
 * the current state of the playback session to the frontend.
 */
@Getter
@Builder
public class PlaybackSessionResponse {

    private final String sessionId;
    private final LocalDate date;
    private final double speed;
    private final String status;
    private final int ticksProcessed;
    private final int totalTicks;
    private final int progressPercent;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Set<Long> instrumentTokens;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
}
