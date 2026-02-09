package com.algotrader.entity;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.LogicOperator;
import com.algotrader.domain.enums.StrategyType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the composite_condition_rules table.
 *
 * <p>Combines multiple individual ConditionRuleEntity entries with AND/OR logic.
 * When the composite condition evaluates to true (all children for AND, any child
 * for OR), the configured action is triggered.
 *
 * <p>Child rule IDs are stored in a separate collection table (composite_rule_children)
 * to allow many-to-many relationships between composites and individual rules.
 */
@Entity
@Table(name = "composite_condition_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompositeConditionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "logic_operator", nullable = false, columnDefinition = "varchar(50)")
    private LogicOperator logicOperator;

    @ElementCollection
    @CollectionTable(name = "composite_rule_children", joinColumns = @JoinColumn(name = "composite_rule_id"))
    @Column(name = "child_rule_id")
    private List<Long> childRuleIds;

    // Action on trigger
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, columnDefinition = "varchar(50)")
    private ConditionActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", columnDefinition = "varchar(50)")
    private StrategyType strategyType;

    @Column(name = "strategy_config", columnDefinition = "JSON")
    private String strategyConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    private ConditionRuleStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
