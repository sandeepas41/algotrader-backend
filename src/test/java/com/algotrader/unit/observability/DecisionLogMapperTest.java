package com.algotrader.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.entity.DecisionLogEntity;
import com.algotrader.mapper.DecisionLogMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Unit tests for the DecisionLogMapper (MapStruct).
 *
 * <p>Verifies domain model <-> entity mapping, including JSON serialization
 * of the dataContext field.
 */
class DecisionLogMapperTest {

    private final DecisionLogMapper decisionLogMapper = Mappers.getMapper(DecisionLogMapper.class);

    @Test
    @DisplayName("toEntity maps all fields correctly")
    void toEntityMapsAllFields() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("spotPrice", 22500.0);
        context.put("atmIV", 15.2);

        DecisionRecord record = DecisionRecord.builder()
                .id(1L)
                .timestamp(LocalDateTime.of(2025, 1, 15, 10, 30, 0))
                .source(DecisionSource.STRATEGY_ENGINE)
                .sourceId("STR-001")
                .decisionType(DecisionType.STRATEGY_ENTRY_TRIGGERED)
                .outcome(DecisionOutcome.TRIGGERED)
                .reasoning("ATM IV above threshold")
                .dataContext(context)
                .severity(DecisionSeverity.INFO)
                .sessionDate(LocalDate.of(2025, 1, 15))
                .build();

        DecisionLogEntity entity = decisionLogMapper.toEntity(record);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getTimestamp()).isEqualTo(record.getTimestamp());
        assertThat(entity.getSource()).isEqualTo(DecisionSource.STRATEGY_ENGINE);
        assertThat(entity.getSourceId()).isEqualTo("STR-001");
        assertThat(entity.getDecisionType()).isEqualTo(DecisionType.STRATEGY_ENTRY_TRIGGERED);
        assertThat(entity.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
        assertThat(entity.getReasoning()).isEqualTo("ATM IV above threshold");
        assertThat(entity.getDataContext()).contains("spotPrice");
        assertThat(entity.getDataContext()).contains("22500.0");
        assertThat(entity.getSeverity()).isEqualTo(DecisionSeverity.INFO);
        assertThat(entity.getSessionDate()).isEqualTo(LocalDate.of(2025, 1, 15));
    }

    @Test
    @DisplayName("toDomain maps all fields correctly")
    void toDomainMapsAllFields() {
        DecisionLogEntity entity = DecisionLogEntity.builder()
                .id(2L)
                .timestamp(LocalDateTime.of(2025, 1, 15, 14, 0, 0))
                .source(DecisionSource.RISK_MANAGER)
                .sourceId("ORD-456")
                .decisionType(DecisionType.RISK_ORDER_REJECTED)
                .outcome(DecisionOutcome.REJECTED)
                .reasoning("Daily loss limit exceeded")
                .dataContext("{\"orderId\":\"ORD-456\",\"approved\":false}")
                .severity(DecisionSeverity.WARNING)
                .sessionDate(LocalDate.of(2025, 1, 15))
                .build();

        DecisionRecord record = decisionLogMapper.toDomain(entity);

        assertThat(record.getId()).isEqualTo(2L);
        assertThat(record.getSource()).isEqualTo(DecisionSource.RISK_MANAGER);
        assertThat(record.getSourceId()).isEqualTo("ORD-456");
        assertThat(record.getDecisionType()).isEqualTo(DecisionType.RISK_ORDER_REJECTED);
        assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(record.getReasoning()).isEqualTo("Daily loss limit exceeded");
        assertThat(record.getDataContext()).containsEntry("orderId", "ORD-456");
        assertThat(record.getDataContext()).containsEntry("approved", false);
        assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.WARNING);
    }

    @Test
    @DisplayName("toEntity handles null dataContext")
    void toEntityHandlesNullContext() {
        DecisionRecord record = DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.KILL_SWITCH)
                .decisionType(DecisionType.KILL_SWITCH_ACTIVATED)
                .outcome(DecisionOutcome.TRIGGERED)
                .reasoning("Manual activation")
                .severity(DecisionSeverity.CRITICAL)
                .sessionDate(LocalDate.now())
                .build();

        DecisionLogEntity entity = decisionLogMapper.toEntity(record);

        assertThat(entity.getDataContext()).isNull();
    }

    @Test
    @DisplayName("toDomain handles null dataContext")
    void toDomainHandlesNullContext() {
        DecisionLogEntity entity = DecisionLogEntity.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.SYSTEM)
                .decisionType(DecisionType.SESSION_EXPIRED)
                .outcome(DecisionOutcome.INFO)
                .reasoning("Session expired at 6 AM")
                .severity(DecisionSeverity.WARNING)
                .sessionDate(LocalDate.now())
                .build();

        DecisionRecord record = decisionLogMapper.toDomain(entity);

        assertThat(record.getDataContext()).isNull();
    }

    @Test
    @DisplayName("toDomainList maps list of entities")
    void toDomainListMaps() {
        DecisionLogEntity entity1 = DecisionLogEntity.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.STRATEGY_ENGINE)
                .sourceId("STR-1")
                .decisionType(DecisionType.STRATEGY_DEPLOYED)
                .outcome(DecisionOutcome.INFO)
                .reasoning("Deployed")
                .severity(DecisionSeverity.INFO)
                .sessionDate(LocalDate.now())
                .build();

        DecisionLogEntity entity2 = DecisionLogEntity.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.STRATEGY_ENGINE)
                .sourceId("STR-2")
                .decisionType(DecisionType.STRATEGY_ARMED)
                .outcome(DecisionOutcome.INFO)
                .reasoning("Armed")
                .severity(DecisionSeverity.INFO)
                .sessionDate(LocalDate.now())
                .build();

        List<DecisionRecord> records = decisionLogMapper.toDomainList(List.of(entity1, entity2));

        assertThat(records).hasSize(2);
        assertThat(records.get(0).getSourceId()).isEqualTo("STR-1");
        assertThat(records.get(1).getSourceId()).isEqualTo("STR-2");
    }

    @Test
    @DisplayName("toEntityList maps list of records")
    void toEntityListMaps() {
        DecisionRecord record1 = DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.RISK_MANAGER)
                .sourceId("ORD-1")
                .decisionType(DecisionType.RISK_ORDER_VALIDATED)
                .outcome(DecisionOutcome.TRIGGERED)
                .reasoning("OK")
                .severity(DecisionSeverity.DEBUG)
                .sessionDate(LocalDate.now())
                .build();

        DecisionRecord record2 = DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.RISK_MANAGER)
                .sourceId("ORD-2")
                .decisionType(DecisionType.RISK_ORDER_REJECTED)
                .outcome(DecisionOutcome.REJECTED)
                .reasoning("Limit exceeded")
                .severity(DecisionSeverity.WARNING)
                .sessionDate(LocalDate.now())
                .build();

        List<DecisionLogEntity> entities = decisionLogMapper.toEntityList(List.of(record1, record2));

        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).getSourceId()).isEqualTo("ORD-1");
        assertThat(entities.get(1).getSourceId()).isEqualTo("ORD-2");
    }
}
