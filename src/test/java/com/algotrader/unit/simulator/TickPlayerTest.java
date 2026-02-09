package com.algotrader.unit.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.config.TickRecorderConfig;
import com.algotrader.domain.model.RecordedTick;
import com.algotrader.event.ReplayCompleteEvent;
import com.algotrader.event.ReplayProgressEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.simulator.PlaybackSession;
import com.algotrader.simulator.PlaybackSession.PlaybackStatus;
import com.algotrader.simulator.TickFileFormat;
import com.algotrader.simulator.TickPlayer;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for TickPlayer (Task 17.1).
 *
 * <p>Verifies: safety guard on LIVE mode, speed clamping, streaming replay,
 * selective replay by time/instrument, pause/resume, and event publishing.
 */
class TickPlayerTest {

    @TempDir
    Path tempDir;

    private TickRecorderConfig tickRecorderConfig;
    private TestEventPublisher eventPublisher;
    private DecisionLogger decisionLogger;
    private TickPlayer tickPlayer;

    @BeforeEach
    void setUp() {
        tickRecorderConfig = new TickRecorderConfig();
        tickRecorderConfig.setRecordingDirectory(tempDir.toString());
        eventPublisher = new TestEventPublisher();
        decisionLogger = new DecisionLogger(eventPublisher, new NoOpDecisionArchiveService());
        tickPlayer = new TickPlayer(tickRecorderConfig, eventPublisher, decisionLogger);
        // Default to PAPER mode for tests
        ReflectionTestUtils.setField(tickPlayer, "tradingMode", "PAPER");
    }

    @Test
    void safetyGuard_preventsReplayInLiveMode() {
        ReflectionTestUtils.setField(tickPlayer, "tradingMode", "LIVE");

        assertThatThrownBy(() -> tickPlayer.startReplay(LocalDate.now(), 1.0, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIVE trading mode");
    }

    @Test
    void startReplay_throwsIfAlreadyReplaying() throws Exception {
        Path file = createTestTickFile(LocalDate.now(), 5, LocalTime.of(9, 15));

        PlaybackSession session = tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);
        assertThat(session.getStatus()).isEqualTo(PlaybackStatus.RUNNING);

        assertThatThrownBy(() -> tickPlayer.startReplay(LocalDate.now(), 1.0, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");

        // Clean up
        tickPlayer.stopReplay();
        waitForReplayComplete();
    }

    @Test
    void startReplay_throwsIfNoRecordingFound() {
        assertThatThrownBy(() -> tickPlayer.startReplay(LocalDate.of(2020, 1, 1), 1.0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No recording found");
    }

    @Test
    void speedClamping_clampsBelowMinimum() throws Exception {
        createTestTickFile(LocalDate.now(), 3, LocalTime.of(9, 15));

        PlaybackSession session = tickPlayer.startReplay(LocalDate.now(), 0.1, null, null, null);
        assertThat(session.getSpeed()).isEqualTo(0.5);

        tickPlayer.stopReplay();
        waitForReplayComplete();
    }

    @Test
    void speedClamping_clampsAboveMaximum() throws Exception {
        createTestTickFile(LocalDate.now(), 3, LocalTime.of(9, 15));

        PlaybackSession session = tickPlayer.startReplay(LocalDate.now(), 20.0, null, null, null);
        assertThat(session.getSpeed()).isEqualTo(10.0);

        tickPlayer.stopReplay();
        waitForReplayComplete();
    }

    @Test
    void replay_publishesTickEventsForAllTicks() throws Exception {
        int tickCount = 5;
        createTestTickFile(LocalDate.now(), tickCount, LocalTime.of(9, 15));

        tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);
        waitForReplayComplete();

        long tickEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof TickEvent)
                .count();
        assertThat(tickEvents).isEqualTo(tickCount);
    }

    @Test
    void replay_publishesReplayCompleteEvent() throws Exception {
        createTestTickFile(LocalDate.now(), 3, LocalTime.of(9, 15));

        tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);
        waitForReplayComplete();

        long completeEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof ReplayCompleteEvent)
                .count();
        assertThat(completeEvents).isEqualTo(1);

        ReplayCompleteEvent event = (ReplayCompleteEvent) eventPublisher.events.stream()
                .filter(e -> e instanceof ReplayCompleteEvent)
                .findFirst()
                .orElseThrow();
        assertThat(event.isCompletedNormally()).isTrue();
        assertThat(event.getTicksProcessed()).isEqualTo(3);
    }

    @Test
    void replay_setsDecisionLoggerReplayMode() throws Exception {
        createTestTickFile(LocalDate.now(), 3, LocalTime.of(9, 15));

        // Before replay
        assertThat(decisionLogger.isReplayMode()).isFalse();

        tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);

        // The replay thread sets replay mode — we can't check synchronously since it's async.
        // Instead, verify after completion that it was reset.
        waitForReplayComplete();

        assertThat(decisionLogger.isReplayMode()).isFalse();
        assertThat(decisionLogger.getReplaySessionId()).isNull();
    }

    @Test
    void selectiveReplay_filtersByInstrumentTokens() throws Exception {
        // Create file with ticks for tokens 100 and 200
        int tickCount = 6;
        LocalDate today = LocalDate.now();
        Path filePath = tempDir.resolve("ticks-" + today + ".bin");
        writeMixedTokenTickFile(filePath, tickCount);

        tickPlayer.startReplay(today, 10.0, null, null, Set.of(100L));
        waitForReplayComplete();

        long tickEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof TickEvent)
                .map(e -> ((TickEvent) e).getTick().getInstrumentToken())
                .filter(token -> token == 100L)
                .count();

        // Only token 100 should be published (3 out of 6 ticks)
        assertThat(tickEvents).isEqualTo(3);
    }

    @Test
    void selectiveReplay_filtersByTimeRange() throws Exception {
        // Create 6 ticks at 09:15:00, 09:15:01, 09:15:02, 09:15:03, 09:15:04, 09:15:05
        // (1-second apart so replay at 10x = 100ms sleep per gap)
        int tickCount = 6;
        LocalDate today = LocalDate.now();
        Path filePath = tempDir.resolve("ticks-" + today + ".bin");
        CRC32 crc = new CRC32();
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)))) {
            TickFileFormat.writeHeader(dos, tickCount, 0);
            for (int i = 0; i < tickCount; i++) {
                RecordedTick tick = RecordedTick.builder()
                        .timestamp(LocalDateTime.of(today, LocalTime.of(9, 15, i)))
                        .instrumentToken(100L)
                        .lastPrice(BigDecimal.valueOf(100.0 + i))
                        .open(BigDecimal.valueOf(100.0))
                        .high(BigDecimal.valueOf(110.0))
                        .low(BigDecimal.valueOf(90.0))
                        .close(BigDecimal.valueOf(99.0))
                        .volume(1000L + i)
                        .oi(BigDecimal.valueOf(5000))
                        .oiChange(BigDecimal.valueOf(100))
                        .receivedAtNanos(System.nanoTime())
                        .build();
                TickFileFormat.writeTick(dos, tick, crc);
            }
        }
        updateFileHeader(filePath, tickCount, crc.getValue());

        // Filter: only ticks between 09:15:02 and 09:15:04
        tickPlayer.startReplay(today, 10.0, LocalTime.of(9, 15, 2), LocalTime.of(9, 15, 4), null);
        waitForReplayComplete();

        long tickEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof TickEvent)
                .count();

        // Ticks at 09:15:02, 09:15:03, 09:15:04 should pass (3 ticks)
        assertThat(tickEvents).isEqualTo(3);
    }

    @Test
    void stopReplay_stopsBeforeCompletion() throws Exception {
        // Create a file with ticks spaced 1 second apart (at 1x = 1s sleep each) so there's time to stop
        LocalDate today = LocalDate.now();
        Path filePath = tempDir.resolve("ticks-" + today + ".bin");
        CRC32 crc = new CRC32();
        int count = 100;
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)))) {
            TickFileFormat.writeHeader(dos, count, 0);
            for (int i = 0; i < count; i++) {
                RecordedTick tick = RecordedTick.builder()
                        .timestamp(LocalDateTime.of(today, LocalTime.of(9, 15).plusSeconds(i)))
                        .instrumentToken(100L)
                        .lastPrice(BigDecimal.valueOf(100.0 + i))
                        .open(BigDecimal.valueOf(100.0))
                        .high(BigDecimal.valueOf(110.0))
                        .low(BigDecimal.valueOf(90.0))
                        .close(BigDecimal.valueOf(99.0))
                        .volume(1000L)
                        .oi(BigDecimal.valueOf(5000))
                        .oiChange(BigDecimal.ZERO)
                        .receivedAtNanos(System.nanoTime())
                        .build();
                TickFileFormat.writeTick(dos, tick, crc);
            }
        }
        updateFileHeader(filePath, count, crc.getValue());

        // Speed 1.0 means 1 second between ticks — gives us ~100 seconds total
        tickPlayer.startReplay(today, 1.0, null, null, null);

        // Give the replay thread time to start and process a few ticks
        Thread.sleep(500);
        tickPlayer.stopReplay();
        waitForReplayComplete();

        PlaybackSession session = tickPlayer.getCurrentSession();
        assertThat(session.getStatus()).isEqualTo(PlaybackStatus.STOPPED);
        assertThat(session.getTicksProcessed()).isLessThan(100);
    }

    @Test
    void pauseAndResume_preservesPosition() throws Exception {
        // Use second-spaced ticks at 1x speed (1s sleep each) so we can pause mid-replay
        LocalDate today = LocalDate.now();
        Path filePath = tempDir.resolve("ticks-" + today + ".bin");
        CRC32 crc = new CRC32();
        int count = 20;
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)))) {
            TickFileFormat.writeHeader(dos, count, 0);
            for (int i = 0; i < count; i++) {
                RecordedTick tick = RecordedTick.builder()
                        .timestamp(LocalDateTime.of(today, LocalTime.of(9, 15).plusSeconds(i)))
                        .instrumentToken(100L)
                        .lastPrice(BigDecimal.valueOf(100.0 + i))
                        .open(BigDecimal.valueOf(100.0))
                        .high(BigDecimal.valueOf(110.0))
                        .low(BigDecimal.valueOf(90.0))
                        .close(BigDecimal.valueOf(99.0))
                        .volume(1000L)
                        .oi(BigDecimal.valueOf(5000))
                        .oiChange(BigDecimal.ZERO)
                        .receivedAtNanos(System.nanoTime())
                        .build();
                TickFileFormat.writeTick(dos, tick, crc);
            }
        }
        updateFileHeader(filePath, count, crc.getValue());

        tickPlayer.startReplay(today, 1.0, null, null, null);
        Thread.sleep(500);

        tickPlayer.pauseReplay();
        assertThat(tickPlayer.getCurrentSession().getStatus()).isEqualTo(PlaybackStatus.PAUSED);

        int ticksAtPause = tickPlayer.getCurrentSession().getTicksProcessed();
        assertThat(ticksAtPause).isGreaterThan(0);

        // Resume at 10x speed so it finishes quickly
        tickPlayer.setSpeed(10.0);
        tickPlayer.resumeReplay();
        waitForReplayComplete();

        assertThat(tickPlayer.getCurrentSession().getTicksProcessed()).isGreaterThan(ticksAtPause);
    }

    @Test
    void setSpeed_adjustsSpeedMidReplay() throws Exception {
        createTestTickFile(LocalDate.now(), 10, LocalTime.of(9, 15));

        PlaybackSession session = tickPlayer.startReplay(LocalDate.now(), 1.0, null, null, null);
        assertThat(session.getSpeed()).isEqualTo(1.0);

        tickPlayer.setSpeed(5.0);
        assertThat(tickPlayer.getCurrentSession().getSpeed()).isEqualTo(5.0);

        tickPlayer.stopReplay();
        waitForReplayComplete();
    }

    @Test
    void replay_sessionCreatedWithCorrectMetadata() throws Exception {
        LocalDate date = LocalDate.now();
        createTestTickFile(date, 5, LocalTime.of(9, 15));

        PlaybackSession session =
                tickPlayer.startReplay(date, 2.0, LocalTime.of(9, 15), LocalTime.of(15, 30), Set.of(100L, 200L));

        assertThat(session.getSessionId()).isNotNull();
        assertThat(session.getDate()).isEqualTo(date);
        assertThat(session.getSpeed()).isEqualTo(2.0);
        assertThat(session.getStartTime()).isEqualTo(LocalTime.of(9, 15));
        assertThat(session.getEndTime()).isEqualTo(LocalTime.of(15, 30));
        assertThat(session.getInstrumentTokens()).containsExactlyInAnyOrder(100L, 200L);
        assertThat(session.getStartedAt()).isNotNull();

        tickPlayer.stopReplay();
        waitForReplayComplete();
    }

    @Test
    void isReplaying_falseWhenNoSession() {
        assertThat(tickPlayer.isReplaying()).isFalse();
    }

    @Test
    void replay_publishesProgressEventsEvery1000Ticks() throws Exception {
        // Create file with > 1000 ticks to trigger progress events
        createTestTickFile(LocalDate.now(), 1500, LocalTime.of(9, 15));

        tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);
        waitForReplayComplete();

        long progressEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof ReplayProgressEvent)
                .count();
        // Should have at least 1 progress event (at 1000 ticks)
        assertThat(progressEvents).isGreaterThanOrEqualTo(1);
    }

    @Test
    void replay_tickEventSourceIsTickPlayer() throws Exception {
        createTestTickFile(LocalDate.now(), 3, LocalTime.of(9, 15));

        tickPlayer.startReplay(LocalDate.now(), 10.0, null, null, null);
        waitForReplayComplete();

        eventPublisher.events.stream()
                .filter(e -> e instanceof TickEvent)
                .map(e -> ((TickEvent) e).getSource())
                .forEach(source -> assertThat(source).isInstanceOf(TickPlayer.class));
    }

    // ---- Test helpers ----

    /** Creates a tick file with ticks spaced 10ms apart (fast for tests). */
    private Path createTestTickFile(LocalDate date, int count, LocalTime startTime) throws Exception {
        Path filePath = tempDir.resolve("ticks-" + date + ".bin");
        CRC32 crc = new CRC32();

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)))) {
            TickFileFormat.writeHeader(dos, count, 0);

            for (int i = 0; i < count; i++) {
                // Space ticks 10ms apart so replay completes instantly at 10x speed
                RecordedTick tick = RecordedTick.builder()
                        .timestamp(LocalDateTime.of(date, startTime.plusNanos(10_000_000L * i)))
                        .instrumentToken(100L)
                        .lastPrice(BigDecimal.valueOf(100.0 + i))
                        .open(BigDecimal.valueOf(100.0))
                        .high(BigDecimal.valueOf(110.0))
                        .low(BigDecimal.valueOf(90.0))
                        .close(BigDecimal.valueOf(99.0))
                        .volume(1000L + i)
                        .oi(BigDecimal.valueOf(5000))
                        .oiChange(BigDecimal.valueOf(100))
                        .receivedAtNanos(System.nanoTime())
                        .build();
                TickFileFormat.writeTick(dos, tick, crc);
            }
        }

        updateFileHeader(filePath, count, crc.getValue());
        return filePath;
    }

    private void writeMixedTokenTickFile(Path filePath, int count) throws Exception {
        CRC32 crc = new CRC32();
        LocalDate today = LocalDate.now();

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(filePath)))) {
            TickFileFormat.writeHeader(dos, count, 0);

            for (int i = 0; i < count; i++) {
                long token = (i % 2 == 0) ? 100L : 200L;
                RecordedTick tick = RecordedTick.builder()
                        .timestamp(LocalDateTime.of(today, LocalTime.of(9, 15).plusNanos(10_000_000L * i)))
                        .instrumentToken(token)
                        .lastPrice(BigDecimal.valueOf(100.0 + i))
                        .open(BigDecimal.valueOf(100.0))
                        .high(BigDecimal.valueOf(110.0))
                        .low(BigDecimal.valueOf(90.0))
                        .close(BigDecimal.valueOf(99.0))
                        .volume(1000L)
                        .oi(BigDecimal.valueOf(5000))
                        .oiChange(BigDecimal.ZERO)
                        .receivedAtNanos(System.nanoTime())
                        .build();
                TickFileFormat.writeTick(dos, tick, crc);
            }
        }

        updateFileHeader(filePath, count, crc.getValue());
    }

    private void updateFileHeader(Path filePath, int count, long crcValue) throws Exception {
        try (var raf = new java.io.RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek(0);
            raf.writeLong(TickFileFormat.MAGIC);
            raf.writeInt(TickFileFormat.VERSION);
            raf.writeInt(count);
            raf.writeLong(System.currentTimeMillis());
            raf.writeLong(crcValue);
        }
    }

    private void waitForReplayComplete() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (!tickPlayer.isReplaying()) {
                Thread.sleep(50); // small extra wait for event publishing
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Replay did not complete within 5 seconds");
    }

    /** Simple event publisher that collects events into a list for assertions. */
    static class TestEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    /** No-op DecisionArchiveService stub for testing. */
    static class NoOpDecisionArchiveService extends com.algotrader.observability.DecisionArchiveService {
        NoOpDecisionArchiveService() {
            super(null);
        }

        @Override
        public void queue(com.algotrader.domain.model.DecisionRecord record) {
            // no-op
        }
    }
}
