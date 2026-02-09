package com.algotrader.unit.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.config.TickRecorderConfig;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.model.RecordedTick;
import com.algotrader.domain.model.RecordingInfo;
import com.algotrader.domain.model.RecordingStats;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.simulator.TickFileFormat;
import com.algotrader.simulator.TickRecorder;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the TickRecorder service.
 *
 * <p>Tests session management (start/stop), tick buffering and flushing,
 * market phase auto-start/stop, file creation, and recording statistics.
 * Uses a temp directory for tick files so tests don't leave artifacts.
 * All tests use public API methods only (no package-private access).
 */
@DisplayName("TickRecorder")
class TickRecorderTest {

    @TempDir
    Path tempDir;

    private TickRecorderConfig tickRecorderConfig;
    private TickRecorder tickRecorder;

    @BeforeEach
    void setUp() {
        tickRecorderConfig = new TickRecorderConfig();
        tickRecorderConfig.setRecordingDirectory(tempDir.toString());
        tickRecorderConfig.setBufferFlushSize(5);
        tickRecorderConfig.setCompressAfterClose(false);
        tickRecorder = new TickRecorder(tickRecorderConfig);
    }

    @Nested
    @DisplayName("Session Management")
    class SessionManagement {

        @Test
        @DisplayName("should start and stop recording")
        void startStop() {
            assertThat(tickRecorder.isRecording()).isFalse();

            tickRecorder.startRecording();
            assertThat(tickRecorder.isRecording()).isTrue();

            tickRecorder.stopRecording();
            assertThat(tickRecorder.isRecording()).isFalse();
        }

        @Test
        @DisplayName("should ignore start when already recording")
        void startWhenAlreadyRecording() {
            tickRecorder.startRecording();
            tickRecorder.startRecording(); // second start should be ignored
            assertThat(tickRecorder.isRecording()).isTrue();
        }

        @Test
        @DisplayName("should ignore stop when not recording")
        void stopWhenNotRecording() {
            tickRecorder.stopRecording(); // should not throw
            assertThat(tickRecorder.isRecording()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tick Recording")
    class TickRecording {

        @Test
        @DisplayName("should ignore ticks when not recording")
        void ignoredWhenNotRecording() {
            TickEvent event = createTickEvent(256001L, 100.0);
            tickRecorder.onTick(event);

            RecordingStats stats = tickRecorder.getCurrentStats();
            assertThat(stats.getTotalTicks()).isZero();
        }

        @Test
        @DisplayName("should buffer ticks when recording")
        void bufferTicks() {
            tickRecorder.startRecording();
            tickRecorder.onTick(createTickEvent(256001L, 100.0));
            tickRecorder.onTick(createTickEvent(256002L, 200.0));

            RecordingStats stats = tickRecorder.getCurrentStats();
            assertThat(stats.getTotalTicks()).isEqualTo(2);
            assertThat(stats.isRecording()).isTrue();
        }

        @Test
        @DisplayName("should auto-flush when buffer reaches configured size")
        void autoFlush() {
            tickRecorderConfig.setBufferFlushSize(3);
            tickRecorder.startRecording();

            // Send exactly 3 ticks to trigger flush
            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.onTick(createTickEvent(2L, 200.0));
            tickRecorder.onTick(createTickEvent(3L, 300.0));

            RecordingStats stats = tickRecorder.getCurrentStats();
            assertThat(stats.getTicksFlushed()).isEqualTo(3);
        }

        @Test
        @DisplayName("should create binary file with correct format on stop")
        void fileCreation() throws IOException {
            tickRecorder.startRecording();

            tickRecorder.onTick(createTickEvent(256001L, 100.0));
            tickRecorder.onTick(createTickEvent(256002L, 200.0));

            // stopRecording() triggers flush to disk
            tickRecorder.stopRecording();

            Path filePath = expectedFilePath();
            assertThat(Files.exists(filePath)).isTrue();

            // File should be header (32 bytes) + 2 ticks (88 bytes each) = 208 bytes
            long fileSize = Files.size(filePath);
            assertThat(fileSize).isEqualTo(TickFileFormat.HEADER_SIZE + 2 * TickFileFormat.TICK_SIZE);

            // Verify header
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(filePath))) {
                TickFileFormat.FileHeader header = TickFileFormat.readHeader(dis);
                assertThat(header.tickCount()).isEqualTo(2);
                assertThat(header.crc32()).isNotEqualTo(0);

                // Read first tick
                RecordedTick tick1 = TickFileFormat.readTick(dis);
                assertThat(tick1.getInstrumentToken()).isEqualTo(256001L);
                assertThat(tick1.getLastPrice().doubleValue()).isEqualTo(100.0);
            }
        }

        @Test
        @DisplayName("should append ticks across auto-flush + final stop flush")
        void multipleFlushes() throws IOException {
            // bufferFlushSize=3 means auto-flush after every 3 ticks
            tickRecorderConfig.setBufferFlushSize(3);
            tickRecorder.startRecording();

            // First 3 ticks trigger auto-flush
            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.onTick(createTickEvent(2L, 200.0));
            tickRecorder.onTick(createTickEvent(3L, 300.0));

            // 4th tick stays in buffer until stop
            tickRecorder.onTick(createTickEvent(4L, 400.0));
            tickRecorder.stopRecording();

            Path filePath = expectedFilePath();
            long fileSize = Files.size(filePath);
            assertThat(fileSize).isEqualTo(TickFileFormat.HEADER_SIZE + 4 * TickFileFormat.TICK_SIZE);

            // Verify header has correct total tick count
            try (DataInputStream dis = new DataInputStream(Files.newInputStream(filePath))) {
                TickFileFormat.FileHeader header = TickFileFormat.readHeader(dis);
                assertThat(header.tickCount()).isEqualTo(4);
            }
        }
    }

    @Nested
    @DisplayName("Market Phase Integration")
    class MarketPhaseIntegration {

        @Test
        @DisplayName("should auto-start on NORMAL phase when configured")
        void autoStartOnNormal() {
            tickRecorderConfig.setAutoStartOnMarketOpen(true);
            assertThat(tickRecorder.isRecording()).isFalse();

            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.PRE_OPEN, MarketPhase.NORMAL);
            tickRecorder.onMarketOpen(event);

            assertThat(tickRecorder.isRecording()).isTrue();
        }

        @Test
        @DisplayName("should not auto-start on NORMAL phase when not configured")
        void noAutoStartWhenDisabled() {
            tickRecorderConfig.setAutoStartOnMarketOpen(false);

            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.PRE_OPEN, MarketPhase.NORMAL);
            tickRecorder.onMarketOpen(event);

            assertThat(tickRecorder.isRecording()).isFalse();
        }

        @Test
        @DisplayName("should auto-stop on CLOSED phase")
        void autoStopOnClosed() {
            tickRecorder.startRecording();
            assertThat(tickRecorder.isRecording()).isTrue();

            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.POST_CLOSE, MarketPhase.CLOSED);
            tickRecorder.onMarketClose(event);

            assertThat(tickRecorder.isRecording()).isFalse();
        }

        @Test
        @DisplayName("should not auto-stop on non-CLOSED phase")
        void noAutoStopOnOtherPhase() {
            tickRecorder.startRecording();

            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.NORMAL, MarketPhase.CLOSING);
            tickRecorder.onMarketClose(event);

            assertThat(tickRecorder.isRecording()).isTrue();
        }
    }

    @Nested
    @DisplayName("Recording Listings")
    class RecordingListings {

        @Test
        @DisplayName("should return empty list when no recordings exist")
        void emptyDirectory() {
            List<RecordingInfo> recordings = tickRecorder.getAvailableRecordings();
            assertThat(recordings).isEmpty();
        }

        @Test
        @DisplayName("should list recorded files with metadata")
        void listRecordedFiles() {
            // Create a recording file by recording and flushing via stop
            tickRecorder.startRecording();
            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.onTick(createTickEvent(2L, 200.0));
            tickRecorder.stopRecording();

            List<RecordingInfo> recordings = tickRecorder.getAvailableRecordings();
            assertThat(recordings).hasSize(1);

            RecordingInfo info = recordings.getFirst();
            assertThat(info.getDate()).isEqualTo(LocalDate.now());
            assertThat(info.getTickCount()).isEqualTo(2);
            assertThat(info.isCompressed()).isFalse();
            assertThat(info.getFileSizeBytes()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Compression")
    class Compression {

        @Test
        @DisplayName("should compress file on stop when configured")
        void compressOnStop() throws IOException {
            tickRecorderConfig.setCompressAfterClose(true);
            tickRecorder.startRecording();
            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.stopRecording();

            Path binPath = expectedFilePath();
            Path gzPath = Path.of(binPath + ".gz");

            // Original .bin should be deleted, .gz should exist
            assertThat(Files.exists(binPath)).isFalse();
            assertThat(Files.exists(gzPath)).isTrue();
            assertThat(Files.size(gzPath)).isGreaterThan(0);
        }

        @Test
        @DisplayName("should not compress when disabled")
        void noCompressWhenDisabled() throws IOException {
            tickRecorderConfig.setCompressAfterClose(false);
            tickRecorder.startRecording();
            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.stopRecording();

            Path binPath = expectedFilePath();
            Path gzPath = Path.of(binPath + ".gz");

            assertThat(Files.exists(binPath)).isTrue();
            assertThat(Files.exists(gzPath)).isFalse();
        }
    }

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("should track recording stats accurately")
        void statsTracking() {
            tickRecorder.startRecording();

            tickRecorder.onTick(createTickEvent(1L, 100.0));
            tickRecorder.onTick(createTickEvent(2L, 200.0));

            RecordingStats stats = tickRecorder.getCurrentStats();
            assertThat(stats.isRecording()).isTrue();
            assertThat(stats.getDate()).isEqualTo(LocalDate.now());
            assertThat(stats.getTotalTicks()).isEqualTo(2);
            assertThat(stats.getStartTime()).isNotNull();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Builds the expected file path using the same convention as TickRecorder.
     */
    private Path expectedFilePath() {
        LocalDate today = LocalDate.now();
        return tempDir.resolve("ticks-" + today.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".bin");
    }

    private TickEvent createTickEvent(long instrumentToken, double lastPrice) {
        Tick tick = Tick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(BigDecimal.valueOf(lastPrice))
                .open(BigDecimal.valueOf(100.0))
                .high(BigDecimal.valueOf(110.0))
                .low(BigDecimal.valueOf(90.0))
                .close(BigDecimal.valueOf(95.0))
                .volume(10000)
                .oi(BigDecimal.valueOf(5000))
                .oiChange(BigDecimal.valueOf(-100))
                .timestamp(LocalDateTime.now())
                .build();

        return new TickEvent(this, tick);
    }
}
