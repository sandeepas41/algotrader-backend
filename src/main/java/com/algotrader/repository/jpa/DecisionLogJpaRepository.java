package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.entity.DecisionLogEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the decision_log table.
 *
 * <p>Supports reviewing automated trading decisions by source, type, outcome,
 * strategy, session date, and time range. Used by the REST API for historical
 * decision log queries and by DecisionArchiveService for batch persistence.
 */
@Repository
public interface DecisionLogJpaRepository extends JpaRepository<DecisionLogEntity, Long> {

    List<DecisionLogEntity> findBySourceIdOrderByTimestampDesc(String sourceId);

    List<DecisionLogEntity> findBySourceOrderByTimestampDesc(DecisionSource source);

    List<DecisionLogEntity> findByDecisionTypeOrderByTimestampDesc(DecisionType decisionType);

    List<DecisionLogEntity> findByOutcomeOrderByTimestampDesc(DecisionOutcome outcome);

    List<DecisionLogEntity> findBySessionDateOrderByTimestampDesc(LocalDate sessionDate);

    @Query("SELECT d FROM DecisionLogEntity d WHERE d.timestamp BETWEEN :from AND :to ORDER BY d.timestamp DESC")
    List<DecisionLogEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT d FROM DecisionLogEntity d WHERE d.sourceId = :sourceId "
            + "AND d.sessionDate = :sessionDate ORDER BY d.timestamp DESC")
    List<DecisionLogEntity> findBySourceIdAndSessionDate(
            @Param("sourceId") String sourceId, @Param("sessionDate") LocalDate sessionDate);

    @Query("SELECT d FROM DecisionLogEntity d WHERE d.severity IN :severities "
            + "AND d.sessionDate = :sessionDate ORDER BY d.timestamp DESC")
    List<DecisionLogEntity> findBySeveritiesAndSessionDate(
            @Param("severities") List<DecisionSeverity> severities, @Param("sessionDate") LocalDate sessionDate);

    long countBySessionDate(LocalDate sessionDate);
}
