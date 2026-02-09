package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the tick_recordings table.
 * Persists raw tick data for specific instruments and time periods,
 * enabling replay analysis and backtesting of strategy behavior.
 * Tick data is stored as JSON to preserve the full tick structure.
 */
@Entity
@Table(name = "tick_recordings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickRecordingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_token")
    private Long instrumentToken;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    /** JSON-serialized tick data (LTP, volume, OI, depth, etc.). */
    @Column(name = "tick_data", columnDefinition = "JSON")
    private String tickData;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
