package com.algotrader.unit.simulator;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.TickRecordingController;
import com.algotrader.api.dto.response.RecordingInfoResponse;
import com.algotrader.api.dto.response.RecordingStatsResponse;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.model.RecordingInfo;
import com.algotrader.domain.model.RecordingStats;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.mapper.RecordingMapper;
import com.algotrader.simulator.TickRecorder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc unit tests for the TickRecordingController.
 *
 * <p>Tests start/stop recording, stats retrieval, and recording listing
 * endpoints. Verifies HTTP status codes, response structure, and
 * conflict handling (409 for duplicate start/stop).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TickRecordingController")
class TickRecordingControllerTest {

    @Mock
    private TickRecorder tickRecorder;

    @Mock
    private RecordingMapper recordingMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TickRecordingController controller = new TickRecordingController(tickRecorder, recordingMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/recordings/start")
    class StartRecording {

        @Test
        @DisplayName("should start recording and return 200")
        void startSuccess() throws Exception {
            when(tickRecorder.isRecording()).thenReturn(false);

            mockMvc.perform(post("/api/recordings/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Recording started"));

            verify(tickRecorder).startRecording();
        }

        @Test
        @DisplayName("should return 409 when already recording")
        void startConflict() throws Exception {
            when(tickRecorder.isRecording()).thenReturn(true);

            mockMvc.perform(post("/api/recordings/start")).andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("POST /api/recordings/stop")
    class StopRecording {

        @Test
        @DisplayName("should stop recording and return 200")
        void stopSuccess() throws Exception {
            when(tickRecorder.isRecording()).thenReturn(true);

            mockMvc.perform(post("/api/recordings/stop"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Recording stopped"));

            verify(tickRecorder).stopRecording();
        }

        @Test
        @DisplayName("should return 409 when not recording")
        void stopConflict() throws Exception {
            when(tickRecorder.isRecording()).thenReturn(false);

            mockMvc.perform(post("/api/recordings/stop")).andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("GET /api/recordings/stats")
    class GetStats {

        @Test
        @DisplayName("should return current recording stats")
        void getStats() throws Exception {
            RecordingStats domainStats = RecordingStats.builder()
                    .recording(true)
                    .date(LocalDate.of(2025, 1, 15))
                    .ticksBuffered(100)
                    .ticksFlushed(5000)
                    .totalTicks(5100)
                    .fileSizeBytes(448832)
                    .startTime(LocalDateTime.of(2025, 1, 15, 9, 15))
                    .build();

            RecordingStatsResponse response = RecordingStatsResponse.builder()
                    .recording(true)
                    .date(LocalDate.of(2025, 1, 15))
                    .ticksBuffered(100)
                    .ticksFlushed(5000)
                    .totalTicks(5100)
                    .fileSizeBytes(448832)
                    .startTime(LocalDateTime.of(2025, 1, 15, 9, 15))
                    .build();

            when(tickRecorder.getCurrentStats()).thenReturn(domainStats);
            when(recordingMapper.toResponse(domainStats)).thenReturn(response);

            mockMvc.perform(get("/api/recordings/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.recording").value(true))
                    .andExpect(jsonPath("$.data.totalTicks").value(5100))
                    .andExpect(jsonPath("$.data.ticksFlushed").value(5000))
                    .andExpect(jsonPath("$.data.ticksBuffered").value(100))
                    .andExpect(jsonPath("$.data.fileSizeBytes").value(448832));
        }
    }

    @Nested
    @DisplayName("GET /api/recordings")
    class ListRecordings {

        @Test
        @DisplayName("should return list of available recordings")
        void listRecordings() throws Exception {
            RecordingInfo info = RecordingInfo.builder()
                    .date(LocalDate.of(2025, 1, 15))
                    .fileSizeBytes(1024000)
                    .tickCount(11500)
                    .compressed(false)
                    .filePath("ticks-2025-01-15.bin")
                    .build();

            RecordingInfoResponse infoResponse = RecordingInfoResponse.builder()
                    .date(LocalDate.of(2025, 1, 15))
                    .fileSizeBytes(1024000)
                    .tickCount(11500)
                    .compressed(false)
                    .filePath("ticks-2025-01-15.bin")
                    .build();

            when(tickRecorder.getAvailableRecordings()).thenReturn(List.of(info));
            when(recordingMapper.toResponseList(List.of(info))).thenReturn(List.of(infoResponse));

            mockMvc.perform(get("/api/recordings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].date").value("2025-01-15"))
                    .andExpect(jsonPath("$.data[0].tickCount").value(11500))
                    .andExpect(jsonPath("$.data[0].compressed").value(false));
        }

        @Test
        @DisplayName("should return empty list when no recordings")
        void emptyList() throws Exception {
            when(tickRecorder.getAvailableRecordings()).thenReturn(List.of());
            when(recordingMapper.toResponseList(List.of())).thenReturn(List.of());

            mockMvc.perform(get("/api/recordings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }
}
