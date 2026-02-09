package com.algotrader.api.controller;

import com.algotrader.api.dto.response.RecordingInfoResponse;
import com.algotrader.api.dto.response.RecordingStatsResponse;
import com.algotrader.mapper.RecordingMapper;
import com.algotrader.simulator.TickRecorder;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for tick recording management.
 *
 * <p>Provides endpoints to start/stop tick recording, query available recordings,
 * and get live statistics for the current session. Used by the frontend's
 * Simulator page (Recording panel) to control and monitor tick capture.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/recordings/start   - Start tick recording</li>
 *   <li>POST /api/recordings/stop    - Stop tick recording</li>
 *   <li>GET  /api/recordings/stats   - Get current session statistics</li>
 *   <li>GET  /api/recordings         - List all available recordings</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class TickRecordingController {

    private final TickRecorder tickRecorder;
    private final RecordingMapper recordingMapper;

    /**
     * Starts tick recording. Returns 409 if already recording.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startRecording() {
        if (tickRecorder.isRecording()) {
            return ResponseEntity.status(409).body(Map.of("message", "Recording already active"));
        }
        tickRecorder.startRecording();
        return ResponseEntity.ok(Map.of("message", "Recording started"));
    }

    /**
     * Stops tick recording. Returns 409 if not currently recording.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopRecording() {
        if (!tickRecorder.isRecording()) {
            return ResponseEntity.status(409).body(Map.of("message", "Recording not active"));
        }
        tickRecorder.stopRecording();
        return ResponseEntity.ok(Map.of("message", "Recording stopped"));
    }

    /**
     * Returns live statistics for the current recording session.
     * Includes tick counts, file size, and recording status.
     */
    @GetMapping("/stats")
    public ResponseEntity<RecordingStatsResponse> getStats() {
        RecordingStatsResponse response = recordingMapper.toResponse(tickRecorder.getCurrentStats());
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all available tick recording files with their metadata.
     * Returns date, file size, tick count, and compression status for each recording.
     */
    @GetMapping
    public ResponseEntity<List<RecordingInfoResponse>> listRecordings() {
        List<RecordingInfoResponse> recordings = recordingMapper.toResponseList(tickRecorder.getAvailableRecordings());
        return ResponseEntity.ok(recordings);
    }
}
