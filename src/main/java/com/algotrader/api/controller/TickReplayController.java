package com.algotrader.api.controller;

import com.algotrader.api.dto.request.ReplayRequest;
import com.algotrader.api.dto.response.PlaybackSessionResponse;
import com.algotrader.mapper.PlaybackMapper;
import com.algotrader.simulator.PlaybackSession;
import com.algotrader.simulator.TickPlayer;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for tick replay (playback) management.
 *
 * <p>Provides endpoints to start/stop/pause/resume tick replay sessions, adjust playback
 * speed, and query session status. Used by the frontend's Simulator page (Playback panel)
 * to control and monitor tick playback.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/replay/start    - Start a new replay session</li>
 *   <li>POST /api/replay/stop     - Stop the current replay</li>
 *   <li>POST /api/replay/pause    - Pause the current replay</li>
 *   <li>POST /api/replay/resume   - Resume a paused replay</li>
 *   <li>POST /api/replay/speed    - Change playback speed mid-replay</li>
 *   <li>GET  /api/replay/status   - Get current session status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/replay")
@RequiredArgsConstructor
public class TickReplayController {

    private final TickPlayer tickPlayer;
    private final PlaybackMapper playbackMapper;

    /**
     * Starts a new replay session. Returns 409 if a replay is already running.
     * Returns 400 if trading mode is LIVE (safety guard).
     */
    @PostMapping("/start")
    public ResponseEntity<PlaybackSessionResponse> startReplay(@RequestBody ReplayRequest request) {
        try {
            PlaybackSession session = tickPlayer.startReplay(
                    request.getDate(),
                    request.getSpeed(),
                    request.getStartTime(),
                    request.getEndTime(),
                    request.getInstrumentTokens());

            return ResponseEntity.ok(playbackMapper.toResponse(session));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Stops the current replay session.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopReplay() {
        if (!tickPlayer.isReplaying()) {
            return ResponseEntity.status(409).body(Map.of("message", "No replay in progress"));
        }
        tickPlayer.stopReplay();
        return ResponseEntity.ok(Map.of("message", "Replay stopped"));
    }

    /**
     * Pauses the current replay. Can be resumed with /resume.
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, String>> pauseReplay() {
        if (!tickPlayer.isReplaying()) {
            return ResponseEntity.status(409).body(Map.of("message", "No replay in progress"));
        }
        tickPlayer.pauseReplay();
        return ResponseEntity.ok(Map.of("message", "Replay paused"));
    }

    /**
     * Resumes a paused replay.
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, String>> resumeReplay() {
        PlaybackSession session = tickPlayer.getCurrentSession();
        if (session == null || session.getStatus() != PlaybackSession.PlaybackStatus.PAUSED) {
            return ResponseEntity.status(409).body(Map.of("message", "No paused replay to resume"));
        }
        tickPlayer.resumeReplay();
        return ResponseEntity.ok(Map.of("message", "Replay resumed"));
    }

    /**
     * Changes the playback speed mid-replay.
     *
     * @param speed new speed multiplier (0.5 to 10.0)
     */
    @PostMapping("/speed")
    public ResponseEntity<Map<String, String>> setSpeed(@RequestParam double speed) {
        tickPlayer.setSpeed(speed);
        return ResponseEntity.ok(Map.of("message", "Speed set to " + speed + "x"));
    }

    /**
     * Returns the current replay session status, or 204 (no content) if no session exists.
     */
    @GetMapping("/status")
    public ResponseEntity<PlaybackSessionResponse> getStatus() {
        PlaybackSession session = tickPlayer.getCurrentSession();
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(playbackMapper.toResponse(session));
    }
}
