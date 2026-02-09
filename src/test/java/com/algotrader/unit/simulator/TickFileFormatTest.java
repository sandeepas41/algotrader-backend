package com.algotrader.unit.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.domain.model.RecordedTick;
import com.algotrader.simulator.TickFileFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.zip.CRC32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the 88-byte binary tick file format and 32-byte file header.
 *
 * <p>Verifies round-trip encoding/decoding of tick records, header validation,
 * CRC32 integrity checks, and edge cases like null BigDecimal fields.
 */
@DisplayName("TickFileFormat")
class TickFileFormatTest {

    @Nested
    @DisplayName("Header")
    class HeaderTests {

        @Test
        @DisplayName("should write and read header with correct magic, version, and tick count")
        void roundTripHeader() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            TickFileFormat.writeHeader(dos, 42, 123456789L);
            dos.flush();

            byte[] bytes = baos.toByteArray();
            assertThat(bytes).hasSize(TickFileFormat.HEADER_SIZE);

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            TickFileFormat.FileHeader header = TickFileFormat.readHeader(dis);

            assertThat(header.version()).isEqualTo(TickFileFormat.VERSION);
            assertThat(header.tickCount()).isEqualTo(42);
            assertThat(header.crc32()).isEqualTo(123456789L);
            assertThat(header.createdAtEpochMs()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should reject header with bad magic number")
        void badMagic() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeLong(0xDEADBEEFL); // wrong magic
            dos.writeInt(1);
            dos.writeInt(0);
            dos.writeLong(0);
            dos.writeLong(0);
            dos.flush();

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertThatThrownBy(() -> TickFileFormat.readHeader(dis))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("bad magic");
        }

        @Test
        @DisplayName("should reject header with unsupported version")
        void unsupportedVersion() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeLong(TickFileFormat.MAGIC);
            dos.writeInt(99); // unsupported version
            dos.writeInt(0);
            dos.writeLong(0);
            dos.writeLong(0);
            dos.flush();

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            assertThatThrownBy(() -> TickFileFormat.readHeader(dis))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Unsupported tick file version");
        }
    }

    @Nested
    @DisplayName("Tick Record")
    class TickRecordTests {

        @Test
        @DisplayName("should write exactly 88 bytes per tick")
        void tickSize() throws IOException {
            RecordedTick tick = buildSampleTick();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            TickFileFormat.writeTick(dos, tick, crc);
            dos.flush();

            assertThat(baos.toByteArray()).hasSize(TickFileFormat.TICK_SIZE);
        }

        @Test
        @DisplayName("should round-trip tick data with full precision")
        void roundTripTick() throws IOException {
            RecordedTick original = buildSampleTick();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            TickFileFormat.writeTick(dos, original, crc);
            dos.flush();

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            RecordedTick read = TickFileFormat.readTick(dis);

            assertThat(read.getInstrumentToken()).isEqualTo(original.getInstrumentToken());
            assertThat(read.getLastPrice().doubleValue())
                    .isEqualTo(original.getLastPrice().doubleValue());
            assertThat(read.getOpen().doubleValue())
                    .isEqualTo(original.getOpen().doubleValue());
            assertThat(read.getHigh().doubleValue())
                    .isEqualTo(original.getHigh().doubleValue());
            assertThat(read.getLow().doubleValue()).isEqualTo(original.getLow().doubleValue());
            assertThat(read.getClose().doubleValue())
                    .isEqualTo(original.getClose().doubleValue());
            assertThat(read.getVolume()).isEqualTo(original.getVolume());
            assertThat(read.getOi().doubleValue()).isEqualTo(original.getOi().doubleValue());
            assertThat(read.getOiChange().doubleValue())
                    .isEqualTo(original.getOiChange().doubleValue());
            assertThat(read.getReceivedAtNanos()).isEqualTo(original.getReceivedAtNanos());
            // Timestamp round-trips through epoch millis, so compare to millisecond precision
            assertThat(read.getTimestamp()).isEqualTo(original.getTimestamp());
        }

        @Test
        @DisplayName("should handle null BigDecimal fields as zero")
        void nullBigDecimalFields() throws IOException {
            RecordedTick tick = RecordedTick.builder()
                    .instrumentToken(256001L)
                    .lastPrice(null)
                    .open(null)
                    .high(null)
                    .low(null)
                    .close(null)
                    .volume(0)
                    .oi(null)
                    .oiChange(null)
                    .timestamp(LocalDateTime.of(2025, 1, 15, 10, 30, 0))
                    .receivedAtNanos(0)
                    .build();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            TickFileFormat.writeTick(dos, tick, crc);
            dos.flush();

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            RecordedTick read = TickFileFormat.readTick(dis);

            assertThat(read.getLastPrice().doubleValue()).isEqualTo(0.0);
            assertThat(read.getOi().doubleValue()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should write multiple ticks and read them back in order")
        void multipleTicks() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            RecordedTick tick1 = buildSampleTick(256001L, 100.50);
            RecordedTick tick2 = buildSampleTick(256002L, 200.75);
            RecordedTick tick3 = buildSampleTick(256003L, 300.25);

            TickFileFormat.writeTick(dos, tick1, crc);
            TickFileFormat.writeTick(dos, tick2, crc);
            TickFileFormat.writeTick(dos, tick3, crc);
            dos.flush();

            assertThat(baos.toByteArray()).hasSize(3 * TickFileFormat.TICK_SIZE);

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
            RecordedTick read1 = TickFileFormat.readTick(dis);
            RecordedTick read2 = TickFileFormat.readTick(dis);
            RecordedTick read3 = TickFileFormat.readTick(dis);

            assertThat(read1.getInstrumentToken()).isEqualTo(256001L);
            assertThat(read2.getInstrumentToken()).isEqualTo(256002L);
            assertThat(read3.getInstrumentToken()).isEqualTo(256003L);
            assertThat(read1.getLastPrice().doubleValue()).isEqualTo(100.50);
            assertThat(read2.getLastPrice().doubleValue()).isEqualTo(200.75);
            assertThat(read3.getLastPrice().doubleValue()).isEqualTo(300.25);
        }
    }

    @Nested
    @DisplayName("CRC32 Checksum")
    class Crc32Tests {

        @Test
        @DisplayName("should accumulate CRC32 across multiple ticks")
        void crcAccumulation() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            TickFileFormat.writeTick(dos, buildSampleTick(1L, 100.0), crc);
            long crcAfterOne = crc.getValue();

            TickFileFormat.writeTick(dos, buildSampleTick(2L, 200.0), crc);
            long crcAfterTwo = crc.getValue();

            // CRC should change as more data is written
            assertThat(crcAfterTwo).isNotEqualTo(crcAfterOne);
            assertThat(crcAfterTwo).isNotEqualTo(0L);
        }

        @Test
        @DisplayName("should match CRC32 computed from raw tick data bytes")
        void crcMatchesComputedValue() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            TickFileFormat.writeTick(dos, buildSampleTick(), crc);
            dos.flush();

            byte[] tickData = baos.toByteArray();
            long computedCrc = TickFileFormat.computeCrc32(tickData);
            assertThat(crc.getValue()).isEqualTo(computedCrc);
        }
    }

    @Nested
    @DisplayName("Full File Round-Trip")
    class FullFileTests {

        @Test
        @DisplayName("should write header + ticks and read them back correctly")
        void fullFileRoundTrip() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            CRC32 crc = new CRC32();

            // Write header placeholder
            TickFileFormat.writeHeader(dos, 0, 0);

            // Write 3 ticks
            TickFileFormat.writeTick(dos, buildSampleTick(1L, 100.0), crc);
            TickFileFormat.writeTick(dos, buildSampleTick(2L, 200.0), crc);
            TickFileFormat.writeTick(dos, buildSampleTick(3L, 300.0), crc);
            dos.flush();

            byte[] fileBytes = baos.toByteArray();
            int expectedSize = TickFileFormat.HEADER_SIZE + 3 * TickFileFormat.TICK_SIZE;
            assertThat(fileBytes).hasSize(expectedSize);

            // Read back
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(fileBytes));
            TickFileFormat.FileHeader header = TickFileFormat.readHeader(dis);
            assertThat(header.version()).isEqualTo(1);

            RecordedTick t1 = TickFileFormat.readTick(dis);
            RecordedTick t2 = TickFileFormat.readTick(dis);
            RecordedTick t3 = TickFileFormat.readTick(dis);

            assertThat(t1.getInstrumentToken()).isEqualTo(1L);
            assertThat(t2.getInstrumentToken()).isEqualTo(2L);
            assertThat(t3.getInstrumentToken()).isEqualTo(3L);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private RecordedTick buildSampleTick() {
        return buildSampleTick(256001L, 24350.75);
    }

    private RecordedTick buildSampleTick(long instrumentToken, double lastPrice) {
        return RecordedTick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(BigDecimal.valueOf(lastPrice))
                .open(BigDecimal.valueOf(24300.00))
                .high(BigDecimal.valueOf(24400.50))
                .low(BigDecimal.valueOf(24250.25))
                .close(BigDecimal.valueOf(24280.00))
                .volume(1_500_000)
                .oi(BigDecimal.valueOf(50000))
                .oiChange(BigDecimal.valueOf(-2500))
                .timestamp(LocalDateTime.of(2025, 1, 15, 10, 30, 0))
                .receivedAtNanos(123456789L)
                .build();
    }
}
