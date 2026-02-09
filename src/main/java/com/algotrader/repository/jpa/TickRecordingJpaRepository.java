package com.algotrader.repository.jpa;

import com.algotrader.entity.TickRecordingEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the tick_recordings table.
 * Supports querying recorded tick data by instrument and time range for replay analysis.
 */
@Repository
public interface TickRecordingJpaRepository extends JpaRepository<TickRecordingEntity, Long> {

    List<TickRecordingEntity> findByInstrumentToken(Long instrumentToken);

    @Query(
            "SELECT t FROM TickRecordingEntity t WHERE t.instrumentToken = :token AND t.recordedAt BETWEEN :from AND :to ORDER BY t.recordedAt ASC")
    List<TickRecordingEntity> findByInstrumentTokenAndDateRange(
            @Param("token") Long token, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<TickRecordingEntity> findByTradingSymbol(String tradingSymbol);
}
