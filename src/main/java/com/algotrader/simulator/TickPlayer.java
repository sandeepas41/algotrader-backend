package com.algotrader.simulator;

import com.algotrader.config.TickRecorderConfig;
import com.algotrader.domain.model.RecordedTick;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.ReplayCompleteEvent;
import com.algotrader.event.ReplayProgressEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.simulator.PlaybackSession.PlaybackStatus;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Replays recorded tick files at configurable speed, feeding ticks into the strategy
 * engine and order processing pipeline as if they were live market data.
 *
 * <p>Core features:
 * <ul>
 *   <li>Streaming chunked loading — reads tick files in chunks of 1000 to avoid OOM on large files</li>
 *   <li>Speed control — 0.5x to 10x real-time, adjustable mid-replay via {@link #setSpeed(double)}</li>
 *   <li>Safety guard — prevents replay during LIVE trading mode to avoid accidental fills</li>
 *   <li>Selective replay — filter by time range (startTime/endTime) and instrument tokens</li>
 *   <li>Pause/resume — preserves position in file and continues from where it left off</li>
 * </ul>
 *
 * <p>The replay thread publishes {@link TickEvent}s with {@code this} as the source, so
 * downstream listeners (like {@link com.algotrader.simulator.TickRecorder}) can distinguish
 * replay ticks from live ticks by checking {@code event.getSource() instanceof TickPlayer}.
 *
 * <p>All decision logs produced during replay are tagged with the sessionId via
 * {@link DecisionLogger#setReplayMode(boolean, String)} for later comparison against
 * live trading decisions.
 */
@Service
public class TickPlayer {

    private static final Logger log = LoggerFactory.getLogger(TickPlayer.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    /** Number of ticks to read from file at once (streaming chunk size). */
    static final int CHUNK_SIZE = 1000;

    /** Progress event interval (publish every N ticks). */
    private static final int PROGRESS_INTERVAL = 1000;

    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 10.0;

    /** Max delay cap (60 seconds) to prevent infinite waits on gap ticks. */
    private static final long MAX_DELAY_MS = 60_000;

    private final TickRecorderConfig tickRecorderConfig;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DecisionLogger decisionLogger;

    @Value("${algotrader.trading-mode:LIVE}")
    private String tradingMode;

    private volatile PlaybackSession currentSession;
    private volatile boolean paused = false;

    public TickPlayer(
            TickRecorderConfig tickRecorderConfig,
            ApplicationEventPublisher applicationEventPublisher,
            DecisionLogger decisionLogger) {
        this.tickRecorderConfig = tickRecorderConfig;
        this.applicationEventPublisher = applicationEventPublisher;
        this.decisionLogger = decisionLogger;
    }

    /**
     * Starts replay of a recorded tick file for the given date.
     *
     * @param date        the date whose recording to replay
     * @param speed       playback speed (0.5x - 10x)
     * @param startTime   optional start time filter (null = from beginning)
     * @param endTime     optional end time filter (null = to end)
     * @param instrumentTokens optional instrument filter (null/empty = all instruments)
     * @return the created PlaybackSession
     * @throws IllegalStateException if a replay is already running or trading mode is LIVE
     */
    public PlaybackSession startReplay(
            LocalDate date, double speed, LocalTime startTime, LocalTime endTime, Set<Long> instrumentTokens) {

        // Safety guard: prevent replay during LIVE mode
        if ("LIVE".equalsIgnoreCase(tradingMode)) {
            throw new IllegalStateException(
                    "Cannot start replay in LIVE trading mode. Switch to PAPER or HYBRID first.");
        }

        if (isReplaying()) {
            throw new IllegalStateException("Replay already in progress: " + currentSession.getSessionId());
        }

        Path filePath = resolveRecordingFile(date);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("No recording found for date: " + date + " at " + filePath);
        }

        double clampedSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));

        String sessionId = UUID.randomUUID().toString();
        PlaybackSession session = PlaybackSession.builder()
                .sessionId(sessionId)
                .date(date)
                .speed(clampedSpeed)
                .status(PlaybackStatus.RUNNING)
                .startTime(startTime)
                .endTime(endTime)
                .instrumentTokens(instrumentTokens)
                .startedAt(LocalDateTime.now(IST))
                .build();

        this.currentSession = session;
        this.paused = false;

        log.info(
                "Starting replay: date={}, speed={}x, session={}, timeRange=[{}-{}], instruments={}",
                date,
                clampedSpeed,
                sessionId,
                startTime,
                endTime,
                instrumentTokens != null ? instrumentTokens.size() : "all");

        // Run replay asynchronously
        CompletableFuture.runAsync(() -> executeReplay(session, filePath));

        return session;
    }

    /**
     * Stops the current replay session.
     */
    public void stopReplay() {
        if (currentSession != null && currentSession.getStatus() == PlaybackStatus.RUNNING) {
            currentSession.setStatus(PlaybackStatus.STOPPED);
            paused = false;
            log.info("Replay stop requested for session: {}", currentSession.getSessionId());
        }
    }

    /**
     * Pauses the current replay. Can be resumed with {@link #resumeReplay()}.
     */
    public void pauseReplay() {
        if (currentSession != null && currentSession.getStatus() == PlaybackStatus.RUNNING) {
            paused = true;
            currentSession.setStatus(PlaybackStatus.PAUSED);
            log.info("Replay paused at tick {}", currentSession.getTicksProcessed());
        }
    }

    /**
     * Resumes a paused replay.
     */
    public void resumeReplay() {
        if (currentSession != null && currentSession.getStatus() == PlaybackStatus.PAUSED) {
            paused = false;
            currentSession.setStatus(PlaybackStatus.RUNNING);
            log.info("Replay resumed from tick {}", currentSession.getTicksProcessed());
        }
    }

    /**
     * Adjusts playback speed mid-replay. Clamped to [0.5, 10.0].
     */
    public void setSpeed(double speed) {
        double clamped = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        if (currentSession != null) {
            currentSession.setSpeed(clamped);
            log.info("Replay speed changed to {}x", clamped);
        }
    }

    /**
     * Returns whether a replay is currently running or paused.
     */
    public boolean isReplaying() {
        return currentSession != null
                && (currentSession.getStatus() == PlaybackStatus.RUNNING
                        || currentSession.getStatus() == PlaybackStatus.PAUSED);
    }

    /**
     * Returns the current playback session, or null if no replay is active.
     */
    public PlaybackSession getCurrentSession() {
        return currentSession;
    }

    // ---- Internal replay loop ----

    /**
     * Reads the tick file in streaming chunks and emits each tick as a TickEvent,
     * applying speed-adjusted delays between ticks based on their original timestamps.
     */
    private void executeReplay(PlaybackSession session, Path filePath) {
        int ticksProcessed = 0;
        boolean completedNormally = false;

        // Tag all decision logs during replay
        decisionLogger.setReplayMode(true, session.getSessionId());

        try (DataInputStream dis =
                new DataInputStream(new BufferedInputStream(new FileInputStream(filePath.toFile())))) {

            // Read and validate header
            TickFileFormat.FileHeader header = TickFileFormat.readHeader(dis);
            session.setTotalTicks(header.tickCount());

            log.info("Replay file loaded: {} ticks, version {}", header.tickCount(), header.version());

            long previousTimestampMs = 0;

            for (int i = 0; i < header.tickCount(); i++) {
                // Check for stop request
                if (session.getStatus() == PlaybackStatus.STOPPED) {
                    log.info("Replay stopped at tick {}/{}", ticksProcessed, header.tickCount());
                    break;
                }

                // Handle pause
                while (paused && session.getStatus() == PlaybackStatus.PAUSED) {
                    Thread.sleep(100);
                }

                // Re-check after pause (might have been stopped while paused)
                if (session.getStatus() == PlaybackStatus.STOPPED) {
                    break;
                }

                RecordedTick recorded = TickFileFormat.readTick(dis);

                // Track timestamp for ALL ticks (including filtered) to preserve timing
                long currentTimestampMs =
                        recorded.getTimestamp().toInstant(IST_OFFSET).toEpochMilli();

                // Apply speed-adjusted delay between ticks
                if (previousTimestampMs > 0 && currentTimestampMs > previousTimestampMs) {
                    long originalDelayMs = currentTimestampMs - previousTimestampMs;
                    long adjustedDelayMs = (long) (originalDelayMs / session.getSpeed());

                    // Cap delay to prevent infinite waits on gap ticks
                    if (adjustedDelayMs > 0 && adjustedDelayMs <= MAX_DELAY_MS) {
                        Thread.sleep(adjustedDelayMs);
                    }
                }

                previousTimestampMs = currentTimestampMs;

                // Apply selective filters (after delay, so timing stays accurate)
                if (!passesFilters(recorded, session)) {
                    continue;
                }

                // Convert RecordedTick to Tick and publish as TickEvent
                Tick tick = toTick(recorded);
                applicationEventPublisher.publishEvent(new TickEvent(this, tick));

                ticksProcessed++;
                session.setTicksProcessed(ticksProcessed);

                // Publish progress event periodically
                if (ticksProcessed % PROGRESS_INTERVAL == 0) {
                    applicationEventPublisher.publishEvent(new ReplayProgressEvent(
                            this, session.getSessionId(), ticksProcessed, header.tickCount(), session.getSpeed()));

                    log.debug(
                            "Replay progress: {}/{} ticks ({}%)",
                            ticksProcessed, header.tickCount(), (ticksProcessed * 100) / header.tickCount());
                }
            }

            // If we reached here without being stopped, replay completed normally
            if (session.getStatus() != PlaybackStatus.STOPPED) {
                completedNormally = true;
            }

        } catch (InterruptedException e) {
            log.info("Replay interrupted at tick {}", ticksProcessed);
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Replay failed: {}", e.getMessage(), e);
            session.setStatus(PlaybackStatus.FAILED);
        } finally {
            decisionLogger.setReplayMode(false, null);

            if (completedNormally) {
                session.setStatus(PlaybackStatus.COMPLETED);
            }

            session.setCompletedAt(LocalDateTime.now(IST));

            applicationEventPublisher.publishEvent(
                    new ReplayCompleteEvent(this, session.getSessionId(), ticksProcessed, completedNormally));

            log.info(
                    "Replay session {} finished: {} ticks processed, status={}",
                    session.getSessionId(),
                    ticksProcessed,
                    session.getStatus());
        }
    }

    /**
     * Checks if a recorded tick passes the session's time and instrument filters.
     */
    private boolean passesFilters(RecordedTick tick, PlaybackSession session) {
        // Instrument filter
        Set<Long> tokens = session.getInstrumentTokens();
        if (tokens != null && !tokens.isEmpty() && !tokens.contains(tick.getInstrumentToken())) {
            return false;
        }

        // Time range filter
        LocalTime tickTime = tick.getTimestamp().toLocalTime();

        if (session.getStartTime() != null && tickTime.isBefore(session.getStartTime())) {
            return false;
        }

        return session.getEndTime() == null || !tickTime.isAfter(session.getEndTime());
    }

    /**
     * Converts a RecordedTick from the binary file to a live Tick model.
     */
    private Tick toTick(RecordedTick recorded) {
        return Tick.builder()
                .instrumentToken(recorded.getInstrumentToken())
                .lastPrice(recorded.getLastPrice())
                .open(recorded.getOpen())
                .high(recorded.getHigh())
                .low(recorded.getLow())
                .close(recorded.getClose())
                .volume(recorded.getVolume())
                .oi(recorded.getOi())
                .oiChange(recorded.getOiChange())
                .timestamp(recorded.getTimestamp())
                .build();
    }

    /**
     * Resolves the recording file path for a date, preferring uncompressed .bin over .bin.gz.
     */
    Path resolveRecordingFile(LocalDate date) {
        Path baseDir = Path.of(tickRecorderConfig.getRecordingDirectory());
        Path binPath = baseDir.resolve("ticks-" + date + ".bin");
        if (Files.exists(binPath)) {
            return binPath;
        }
        // #TODO: Support decompressing .bin.gz files for replay (Task 17 enhancement)
        return binPath;
    }
}
