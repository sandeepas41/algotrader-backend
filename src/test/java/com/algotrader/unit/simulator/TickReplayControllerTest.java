package com.algotrader.unit.simulator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.TickReplayController;
import com.algotrader.api.dto.response.PlaybackSessionResponse;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.mapper.PlaybackMapper;
import com.algotrader.simulator.PlaybackSession;
import com.algotrader.simulator.PlaybackSession.PlaybackStatus;
import com.algotrader.simulator.TickPlayer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for TickReplayController (Task 17.1 REST API).
 *
 * <p>Verifies: start replay, stop/pause/resume, speed control, status query, and error handling.
 */
class TickReplayControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TickPlayer tickPlayer;

    @Mock
    private PlaybackMapper playbackMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        TickReplayController controller = new TickReplayController(tickPlayer, playbackMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startReplay_returnsSession() throws Exception {
        LocalDate date = LocalDate.of(2025, 1, 15);
        PlaybackSession session = PlaybackSession.builder()
                .sessionId("test-session-id")
                .date(date)
                .speed(2.0)
                .status(PlaybackStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build();

        PlaybackSessionResponse response = PlaybackSessionResponse.builder()
                .sessionId("test-session-id")
                .date(date)
                .speed(2.0)
                .status("RUNNING")
                .build();

        when(tickPlayer.startReplay(any(), anyDouble(), any(), any(), any())).thenReturn(session);
        when(playbackMapper.toResponse(session)).thenReturn(response);

        String body = """
                {"date":"2025-01-15","speed":2.0}
                """;

        mockMvc.perform(post("/api/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("test-session-id"))
                .andExpect(jsonPath("$.speed").value(2.0))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void startReplay_returns409WhenAlreadyReplaying() throws Exception {
        when(tickPlayer.startReplay(any(), anyDouble(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Replay already in progress"));

        String body = """
                {"date":"2025-01-15","speed":1.0}
                """;

        mockMvc.perform(post("/api/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void startReplay_returns400WhenNoRecording() throws Exception {
        when(tickPlayer.startReplay(any(), anyDouble(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("No recording found"));

        String body = """
                {"date":"2025-01-15","speed":1.0}
                """;

        mockMvc.perform(post("/api/replay/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stopReplay_returnsOk() throws Exception {
        when(tickPlayer.isReplaying()).thenReturn(true);
        doNothing().when(tickPlayer).stopReplay();

        mockMvc.perform(post("/api/replay/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Replay stopped"));
    }

    @Test
    void stopReplay_returns409WhenNotReplaying() throws Exception {
        when(tickPlayer.isReplaying()).thenReturn(false);

        mockMvc.perform(post("/api/replay/stop"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No replay in progress"));
    }

    @Test
    void pauseReplay_returnsOk() throws Exception {
        when(tickPlayer.isReplaying()).thenReturn(true);
        doNothing().when(tickPlayer).pauseReplay();

        mockMvc.perform(post("/api/replay/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Replay paused"));
    }

    @Test
    void resumeReplay_returnsOk() throws Exception {
        PlaybackSession session =
                PlaybackSession.builder().status(PlaybackStatus.PAUSED).build();
        when(tickPlayer.getCurrentSession()).thenReturn(session);
        doNothing().when(tickPlayer).resumeReplay();

        mockMvc.perform(post("/api/replay/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Replay resumed"));
    }

    @Test
    void resumeReplay_returns409WhenNotPaused() throws Exception {
        when(tickPlayer.getCurrentSession()).thenReturn(null);

        mockMvc.perform(post("/api/replay/resume")).andExpect(status().isConflict());
    }

    @Test
    void setSpeed_returnsOk() throws Exception {
        doNothing().when(tickPlayer).setSpeed(anyDouble());

        mockMvc.perform(post("/api/replay/speed").param("speed", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Speed set to 5.0x"));
    }

    @Test
    void getStatus_returnsSession() throws Exception {
        PlaybackSession session = PlaybackSession.builder()
                .sessionId("test-id")
                .status(PlaybackStatus.RUNNING)
                .build();

        PlaybackSessionResponse response = PlaybackSessionResponse.builder()
                .sessionId("test-id")
                .status("RUNNING")
                .build();

        when(tickPlayer.getCurrentSession()).thenReturn(session);
        when(playbackMapper.toResponse(session)).thenReturn(response);

        mockMvc.perform(get("/api/replay/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("test-id"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getStatus_returns204WhenNoSession() throws Exception {
        when(tickPlayer.getCurrentSession()).thenReturn(null);

        mockMvc.perform(get("/api/replay/status")).andExpect(status().isNoContent());
    }
}
