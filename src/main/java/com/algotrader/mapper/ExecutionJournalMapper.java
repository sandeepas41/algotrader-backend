package com.algotrader.mapper;

import com.algotrader.domain.model.ExecutionJournal;
import com.algotrader.entity.ExecutionJournalEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between ExecutionJournal domain model and ExecutionJournalEntity.
 * Straightforward 1:1 field mapping — all fields match between domain and entity.
 */
@Mapper
public interface ExecutionJournalMapper {

    ExecutionJournalEntity toEntity(ExecutionJournal executionJournal);

    ExecutionJournal toDomain(ExecutionJournalEntity entity);

    List<ExecutionJournal> toDomainList(List<ExecutionJournalEntity> entities);
}
