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
 * JPA entity for the trade_notes table.
 * Journal entries for post-trade analysis and learning — traders attach notes to
 * strategies or specific runs to record observations, market conditions, and lessons learned.
 * Used by the trade journal UI and tax reporting.
 */
@Entity
@Table(name = "trade_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Strategy this note is about. Null for general market notes. */
    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    /** Specific run within the strategy. Null for strategy-level notes. */
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Comma-separated tags (e.g., "adjustment,delta-hedge,nifty"). */
    @Column(length = 255)
    private String tags;

    /** Quality rating 1-5. Null if not rated. */
    @Column(name = "star_rating")
    private Integer starRating;

    /** Market condition at the time (e.g., "trending", "range-bound", "volatile"). */
    @Column(name = "market_condition", length = 50)
    private String marketCondition;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
