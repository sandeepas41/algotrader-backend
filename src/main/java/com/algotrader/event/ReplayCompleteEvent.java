package com.algotrader.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published when a tick replay session finishes (either completed or stopped).
 *
 * <p>The WebSocket handler pushes this to /topic/replay/complete so the frontend
 * can update the PlaybackPanel UI and re-enable controls.
 */
public class ReplayCompleteEvent extends ApplicationEvent {

    private final String sessionId;
    private final int ticksProcessed;
    private final boolean completedNormally;

    public ReplayCompleteEvent(Object source, String sessionId, int ticksProcessed, boolean completedNormally) {
        super(source);
        this.sessionId = sessionId;
        this.ticksProcessed = ticksProcessed;
        this.completedNormally = completedNormally;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getTicksProcessed() {
        return ticksProcessed;
    }

    /**
     * True if the replay ran through all ticks. False if stopped or errored.
     */
    public boolean isCompletedNormally() {
        return completedNormally;
    }
}
