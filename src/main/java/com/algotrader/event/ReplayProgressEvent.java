package com.algotrader.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published periodically during tick replay to report progress to the frontend.
 *
 * <p>The WebSocket handler listens for these events and pushes them to the
 * /topic/replay/progress STOMP destination, enabling the PlaybackPanel to show
 * a real-time progress bar and tick counter.
 *
 * <p>Published every 1000 ticks during replay (configurable in TickPlayer).
 */
public class ReplayProgressEvent extends ApplicationEvent {

    private final String sessionId;
    private final int ticksProcessed;
    private final int totalTicks;
    private final double speed;

    public ReplayProgressEvent(Object source, String sessionId, int ticksProcessed, int totalTicks, double speed) {
        super(source);
        this.sessionId = sessionId;
        this.ticksProcessed = ticksProcessed;
        this.totalTicks = totalTicks;
        this.speed = speed;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getTicksProcessed() {
        return ticksProcessed;
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public double getSpeed() {
        return speed;
    }

    /**
     * Progress as a percentage (0-100).
     */
    public int getProgressPercent() {
        return totalTicks > 0 ? (ticksProcessed * 100) / totalTicks : 0;
    }
}
