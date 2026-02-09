package com.algotrader.simulator;

import com.algotrader.domain.model.RecordedTick;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.zip.CRC32;

/**
 * Binary tick file format handler.
 *
 * <p>Defines the 88-byte per-tick binary format and the file header structure.
 * All files start with a 32-byte header containing a magic number, version,
 * tick count, and CRC32 checksum for integrity validation.
 *
 * <p>Header layout (32 bytes):
 * <ul>
 *   <li>magic(8): 0x5449434B46494C45 ("TICKFILE" in ASCII)</li>
 *   <li>version(4): format version (currently 1)</li>
 *   <li>tickCount(4): number of ticks in the file</li>
 *   <li>createdAtEpochMs(8): file creation timestamp</li>
 *   <li>crc32(8): CRC32 checksum of all tick data (after header)</li>
 * </ul>
 *
 * <p>Per-tick layout (88 bytes):
 * <ul>
 *   <li>timestampEpochMs(8) + instrumentToken(8) + lastPrice(8) + open(8) +
 *       high(8) + low(8) + close(8) + volume(8) + oi(8) + oiChange(8) +
 *       receivedAtNanos(8)</li>
 * </ul>
 */
public final class TickFileFormat {

    public static final long MAGIC = 0x5449434B46494C45L; // "TICKFILE"
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 32;
    public static final int TICK_SIZE = 88;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TickFileFormat() {}

    /**
     * Writes the file header. CRC32 is initially 0 and must be updated after all ticks are written.
     */
    public static void writeHeader(DataOutputStream dos, int tickCount, long crc32) throws IOException {
        dos.writeLong(MAGIC);
        dos.writeInt(VERSION);
        dos.writeInt(tickCount);
        dos.writeLong(System.currentTimeMillis());
        dos.writeLong(crc32);
    }

    /**
     * Writes a single tick record (88 bytes) and updates the CRC32 accumulator.
     */
    public static void writeTick(DataOutputStream dos, RecordedTick tick, CRC32 crc) throws IOException {
        long timestampMs = tick.getTimestamp().atZone(IST).toInstant().toEpochMilli();

        byte[] buffer = new byte[TICK_SIZE];
        int offset = 0;
        offset = putLong(buffer, offset, timestampMs);
        offset = putLong(buffer, offset, tick.getInstrumentToken());
        offset = putDouble(buffer, offset, toDouble(tick.getLastPrice()));
        offset = putDouble(buffer, offset, toDouble(tick.getOpen()));
        offset = putDouble(buffer, offset, toDouble(tick.getHigh()));
        offset = putDouble(buffer, offset, toDouble(tick.getLow()));
        offset = putDouble(buffer, offset, toDouble(tick.getClose()));
        offset = putLong(buffer, offset, tick.getVolume());
        offset = putDouble(buffer, offset, toDouble(tick.getOi()));
        offset = putDouble(buffer, offset, toDouble(tick.getOiChange()));
        putLong(buffer, offset, tick.getReceivedAtNanos());

        crc.update(buffer);
        dos.write(buffer);
    }

    /**
     * Reads a single tick record (88 bytes) from the input stream.
     */
    public static RecordedTick readTick(DataInputStream dis) throws IOException {
        long timestampMs = dis.readLong();
        long instrumentToken = dis.readLong();
        double lastPrice = dis.readDouble();
        double open = dis.readDouble();
        double high = dis.readDouble();
        double low = dis.readDouble();
        double close = dis.readDouble();
        long volume = dis.readLong();
        double oi = dis.readDouble();
        double oiChange = dis.readDouble();
        long receivedAtNanos = dis.readLong();

        LocalDateTime timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), IST);

        return RecordedTick.builder()
                .timestamp(timestamp)
                .instrumentToken(instrumentToken)
                .lastPrice(BigDecimal.valueOf(lastPrice))
                .open(BigDecimal.valueOf(open))
                .high(BigDecimal.valueOf(high))
                .low(BigDecimal.valueOf(low))
                .close(BigDecimal.valueOf(close))
                .volume(volume)
                .oi(BigDecimal.valueOf(oi))
                .oiChange(BigDecimal.valueOf(oiChange))
                .receivedAtNanos(receivedAtNanos)
                .build();
    }

    /**
     * Validates the file header. Returns the tick count if valid, throws if corrupt.
     */
    public static FileHeader readHeader(DataInputStream dis) throws IOException {
        long magic = dis.readLong();
        if (magic != MAGIC) {
            throw new IOException("Invalid tick file: bad magic number");
        }
        int version = dis.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported tick file version: " + version);
        }
        int tickCount = dis.readInt();
        long createdAtMs = dis.readLong();
        long crc32 = dis.readLong();

        return new FileHeader(version, tickCount, createdAtMs, crc32);
    }

    /**
     * Computes the CRC32 checksum of all tick data bytes (excluding the header).
     */
    public static long computeCrc32(byte[] tickData) {
        CRC32 crc = new CRC32();
        crc.update(tickData);
        return crc.getValue();
    }

    // Helper: BigDecimal to double, treating null as 0
    private static double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    // Helper: write long to byte array in big-endian
    private static int putLong(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value >>> 56);
        buffer[offset + 1] = (byte) (value >>> 48);
        buffer[offset + 2] = (byte) (value >>> 40);
        buffer[offset + 3] = (byte) (value >>> 32);
        buffer[offset + 4] = (byte) (value >>> 24);
        buffer[offset + 5] = (byte) (value >>> 16);
        buffer[offset + 6] = (byte) (value >>> 8);
        buffer[offset + 7] = (byte) value;
        return offset + 8;
    }

    // Helper: write double to byte array in big-endian (via Double.doubleToLongBits)
    private static int putDouble(byte[] buffer, int offset, double value) {
        return putLong(buffer, offset, Double.doubleToLongBits(value));
    }

    /**
     * Parsed file header.
     */
    public record FileHeader(int version, int tickCount, long createdAtEpochMs, long crc32) {}
}
