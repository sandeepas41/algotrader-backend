package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.entity.ExecutionJournalEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the execution_journal table.
 * On startup, StartupRecoveryService queries for incomplete journals (IN_PROGRESS,
 * REQUIRES_RECOVERY) and resolves them to prevent half-executed strategies after a crash.
 */
@Repository
public interface ExecutionJournalJpaRepository extends JpaRepository<ExecutionJournalEntity, Long> {

    List<ExecutionJournalEntity> findByExecutionGroupId(String executionGroupId);

    List<ExecutionJournalEntity> findByStrategyId(String strategyId);

    List<ExecutionJournalEntity> findByStatus(JournalStatus status);

    List<ExecutionJournalEntity> findByStatusIn(List<JournalStatus> statuses);
}
