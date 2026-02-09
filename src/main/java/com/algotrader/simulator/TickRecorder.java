package com.algotrader.simulator;

import com.algotrader.config.TickRecorderConfig;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.model.RecordedTick;
import com.algotrader.domain.model.RecordingInfo;
import com.algotrader.domain.model.RecordingStats;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.event.TickEvent;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Records all market ticks during trading hours into binary files for later replay and analysis.
 *
 * <p>Listens for {@link TickEvent}s and buffers {@link RecordedTick} objects in memory, flushing
 * periodically to disk in the 88-byte binary format defined by {@link TickFileFormat}. Each trading
 * day produces a separate file under the configured recording directory.
 *
 * <p>Lifecycle is tied to market phases:
 * <ul>
 *   <li>NORMAL phase: auto-starts recording (if configured)</li>
 *   <li>CLOSED phase: stops recording, flushes remaining buffer, optionally compresses the file</li>
 * </ul>
 *
 * <p>Files are written with a 32-byte header (magic, version, tick count, CRC32) followed by
 * N x 88-byte tick records. The header is updated on each flush with the correct tick count
 * and CRC32 checksum for integrity validation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TickRecorder {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TickRecorderConfig tickRecorderConfig;

    /** Buffered ticks per trading day, awaiting flush to disk. */
    private final Map<LocalDate, List<RecordedTick>> dailyBuffer = new ConcurrentHashMap<>();

    /** Running count of ticks recorded in the current session. */
    private final AtomicLong sessionTickCount = new AtomicLong(0);

    /** Total ticks flushed to disk for the current day (across all flushes). */
    private final AtomicLong totalFlushedCount = new AtomicLong(0);

    /** CRC32 accumulator maintained across flushes for the current day's file. */
    private final CRC32 dailyCrc = new CRC32();

    private volatile boolean recording = false;
    private volatile LocalDateTime recordingStartTime;

    /**
     * Records every tick during market hours. Converts the live Tick to a RecordedTick
     * and buffers it. When the buffer reaches the configured flush size, triggers a flush.
     *
     * <p>Order(20) ensures this runs after all critical tick processors (P&L, risk, strategies).
     */
    @EventListener
    @Order(20)
    public void onTick(TickEvent event) {
        if (!recording) {
            return;
        }

        Tick tick = event.getTick();
        LocalDate today = LocalDate.now(IST);

        RecordedTick recorded = RecordedTick.builder()
                .instrumentToken(tick.getInstrumentToken())
                .lastPrice(tick.getLastPrice())
                .open(tick.getOpen())
                .high(tick.getHigh())
                .low(tick.getLow())
                .close(tick.getClose())
                .volume(tick.getVolume())
                .oi(tick.getOi())
                .oiChange(tick.getOiChange())
                .timestamp(tick.getTimestamp())
                .receivedAtNanos(event.getReceivedAt())
                .build();

        dailyBuffer.computeIfAbsent(today, k -> new CopyOnWriteArrayList<>()).add(recorded);

        long count = sessionTickCount.incrementAndGet();
        if (count % tickRecorderConfig.getBufferFlushSize() == 0) {
            flushToDisk(today);
        }

        if (count % 10_000 == 0) {
            log.debug("Tick recorder: {} ticks buffered in current session", count);
        }
    }

    /**
     * Auto-starts recording when market transitions to NORMAL phase.
     */
    @EventListener
    public void onMarketOpen(MarketStatusEvent event) {
        if (event.getCurrentPhase() == MarketPhase.NORMAL && tickRecorderConfig.isAutoStartOnMarketOpen()) {
            startRecording();
        }
    }

    /**
     * Auto-stops recording when market transitions to CLOSED phase.
     * Flushes remaining buffer and optionally compresses the file.
     */
    @EventListener
    public void onMarketClose(MarketStatusEvent event) {
        if (event.getCurrentPhase() == MarketPhase.CLOSED && recording) {
            stopRecording();
        }
    }

    /**
     * Periodic flush to prevent memory buildup during long market sessions.
     * Runs at the interval configured in {@link TickRecorderConfig#getFlushIntervalMs()}.
     */
    @Scheduled(fixedRateString = "${algotrader.tick-recorder.flush-interval-ms:300000}")
    public void periodicFlush() {
        if (recording) {
            flushToDisk(LocalDate.now(IST));
        }
    }

    /**
     * Manually starts recording. Resets session counters and CRC accumulator.
     */
    public void startRecording() {
        if (recording) {
            log.warn("Tick recording already active, ignoring start request");
            return;
        }

        sessionTickCount.set(0);
        totalFlushedCount.set(0);
        dailyCrc.reset();
        recordingStartTime = LocalDateTime.now(IST);
        recording = true;

        log.info("Tick recording started");
    }

    /**
     * Manually stops recording. Flushes remaining buffer and compresses if configured.
     */
    public void stopRecording() {
        if (!recording) {
            log.warn("Tick recording not active, ignoring stop request");
            return;
        }

        recording = false;
        LocalDate today = LocalDate.now(IST);
        flushToDisk(today);

        long totalTicks = totalFlushedCount.get();
        log.info("Tick recording stopped. {} ticks recorded today.", totalTicks);

        if (tickRecorderConfig.isCompressAfterClose() && totalTicks > 0) {
            compressFile(today);
        }

        recordingStartTime = null;
    }

    /**
     * Returns whether the recorder is currently active.
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Returns statistics for the current recording session.
     */
    public RecordingStats getCurrentStats() {
        long buffered = dailyBuffer.values().stream().mapToLong(List::size).sum();
        long flushed = totalFlushedCount.get();
        LocalDate today = LocalDate.now(IST);
        Path filePath = getRecordingFilePath(today);

        long fileSize = 0;
        if (Files.exists(filePath)) {
            try {
                fileSize = Files.size(filePath);
            } catch (IOException e) {
                log.warn("Failed to read file size for {}", filePath, e);
            }
        }

        return RecordingStats.builder()
                .recording(recording)
                .date(today)
                .ticksBuffered(buffered)
                .ticksFlushed(flushed)
                .totalTicks(buffered + flushed)
                .fileSizeBytes(fileSize)
                .startTime(recordingStartTime)
                .build();
    }

    /**
     * Lists all available recording files in the recording directory.
     */
    public List<RecordingInfo> getAvailableRecordings() {
        Path baseDir = Path.of(tickRecorderConfig.getRecordingDirectory());
        if (!Files.exists(baseDir)) {
            return List.of();
        }

        List<RecordingInfo> recordings = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("ticks-"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".bin") || name.endsWith(".bin.gz");
                    })
                    .sorted()
                    .forEach(p -> {
                        try {
                            String name = p.getFileName().toString();
                            boolean compressed = name.endsWith(".gz");
                            String datePart = name.replace("ticks-", "")
                                    .replace(".bin.gz", "")
                                    .replace(".bin", "");
                            LocalDate date = LocalDate.parse(datePart, DATE_FORMAT);

                            int tickCount = 0;
                            long fileSize = Files.size(p);

                            // For uncompressed files, calculate tick count from file size
                            if (!compressed && fileSize > TickFileFormat.HEADER_SIZE) {
                                tickCount = (int) ((fileSize - TickFileFormat.HEADER_SIZE) / TickFileFormat.TICK_SIZE);
                            }

                            recordings.add(RecordingInfo.builder()
                                    .date(date)
                                    .fileSizeBytes(fileSize)
                                    .tickCount(tickCount)
                                    .compressed(compressed)
                                    .filePath(p.getFileName().toString())
                                    .build());
                        } catch (Exception e) {
                            log.warn("Failed to read recording info for {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list recordings directory: {}", e.getMessage(), e);
        }

        return recordings;
    }

    /**
     * Flushes buffered ticks to the binary file. Creates the file with header if it doesn't exist,
     * otherwise appends tick data and updates the header with new tick count and CRC32.
     */
    void flushToDisk(LocalDate date) {
        List<RecordedTick> ticks = dailyBuffer.get(date);
        if (ticks == null || ticks.isEmpty()) {
            return;
        }

        // Snapshot and clear the buffer atomically
        List<RecordedTick> snapshot;
        synchronized (ticks) {
            snapshot = new ArrayList<>(ticks);
            ticks.clear();
        }

        if (snapshot.isEmpty()) {
            return;
        }

        Path filePath = getRecordingFilePath(date);
        try {
            Files.createDirectories(filePath.getParent());

            boolean newFile = !Files.exists(filePath);

            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                    filePath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)))) {

                if (newFile) {
                    // Write placeholder header — will be updated after flush
                    TickFileFormat.writeHeader(dos, 0, 0);
                }

                for (RecordedTick tick : snapshot) {
                    TickFileFormat.writeTick(dos, tick, dailyCrc);
                }
            }

            long flushed = totalFlushedCount.addAndGet(snapshot.size());

            // Update header with correct tick count and CRC32
            updateFileHeader(filePath, (int) flushed, dailyCrc.getValue());

            log.debug("Flushed {} ticks to {} (total: {})", snapshot.size(), filePath, flushed);

        } catch (IOException e) {
            log.error("Failed to flush ticks to disk: {}", e.getMessage(), e);
            // Re-add ticks to buffer so they aren't lost
            dailyBuffer.computeIfAbsent(date, k -> new CopyOnWriteArrayList<>()).addAll(snapshot);
        }
    }

    /**
     * Updates the file header in-place with the final tick count and CRC32 checksum.
     * Uses RandomAccessFile to seek to the header position and overwrite.
     */
    private void updateFileHeader(Path filePath, int tickCount, long crc32) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek(0);
            raf.writeLong(TickFileFormat.MAGIC);
            raf.writeInt(TickFileFormat.VERSION);
            raf.writeInt(tickCount);
            raf.writeLong(System.currentTimeMillis());
            raf.writeLong(crc32);
        }
    }

    /**
     * Compresses a day's tick file using gzip. The original .bin file is deleted after compression.
     */
    private void compressFile(LocalDate date) {
        Path sourcePath = getRecordingFilePath(date);
        if (!Files.exists(sourcePath)) {
            return;
        }

        Path gzPath = Path.of(sourcePath + ".gz");
        try {
            try (var in = Files.newInputStream(sourcePath);
                    var out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(gzPath)))) {
                in.transferTo(out);
            }

            Files.delete(sourcePath);
            log.info("Compressed tick file: {} -> {}", sourcePath.getFileName(), gzPath.getFileName());

        } catch (IOException e) {
            log.error("Failed to compress tick file {}: {}", sourcePath, e.getMessage(), e);
            // Clean up partial gz file
            try {
                Files.deleteIfExists(gzPath);
            } catch (IOException ex) {
                log.warn("Failed to clean up partial gz file: {}", gzPath, ex);
            }
        }
    }

    Path getRecordingFilePath(LocalDate date) {
        return Path.of(tickRecorderConfig.getRecordingDirectory(), "ticks-" + date.format(DATE_FORMAT) + ".bin");
    }
}
