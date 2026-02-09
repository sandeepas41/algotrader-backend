package com.algotrader.unit.condition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.ConditionController;
import com.algotrader.condition.ConditionEngine;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionOperator;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.entity.ConditionRuleEntity;
import com.algotrader.entity.ConditionTriggerHistoryEntity;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.indicator.IndicatorType;
import com.algotrader.repository.jpa.CompositeConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionTriggerHistoryJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the ConditionController.
 * Tests CRUD operations, pause/activate lifecycle, and trigger history endpoints.
 */
@ExtendWith(MockitoExtension.class)
class ConditionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConditionRuleJpaRepository conditionRuleJpaRepository;

    @Mock
    private CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository;

    @Mock
    private ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository;

    @Mock
    private ConditionEngine conditionEngine;

    @BeforeEach
    void setUp() {
        ConditionController controller = new ConditionController(
                conditionRuleJpaRepository,
                compositeConditionRuleJpaRepository,
                conditionTriggerHistoryJpaRepository,
                conditionEngine);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .build();
    }

    private ConditionRuleEntity buildEntity(Long id) {
        return ConditionRuleEntity.builder()
                .id(id)
                .name("High RSI Alert")
                .description("Alert when RSI > 70")
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY 50")
                .underlying("NIFTY")
                .indicatorType(IndicatorType.RSI)
                .indicatorPeriod(14)
                .operator(ConditionOperator.GT)
                .thresholdValue(BigDecimal.valueOf(70))
                .evaluationMode(EvaluationMode.INTERVAL_5M)
                .actionType(ConditionActionType.ALERT_ONLY)
                .status(ConditionRuleStatus.ACTIVE)
                .triggerCount(0)
                .cooldownMinutes(30)
                .createdAt(LocalDateTime.of(2025, 2, 10, 9, 0))
                .build();
    }

    @Test
    @DisplayName("POST /api/conditions creates a new rule and returns 201")
    void createRule() throws Exception {
        ConditionRuleEntity saved = buildEntity(1L);
        when(conditionRuleJpaRepository.save(any())).thenReturn(saved);
        doNothing().when(conditionEngine).loadRules();

        String json = """
                {
                    "name": "High RSI Alert",
                    "description": "Alert when RSI > 70",
                    "instrumentToken": 256265,
                    "tradingSymbol": "NIFTY 50",
                    "underlying": "NIFTY",
                    "indicatorType": "RSI",
                    "indicatorPeriod": 14,
                    "operator": "GT",
                    "thresholdValue": 70,
                    "evaluationMode": "INTERVAL_5M",
                    "actionType": "ALERT_ONLY",
                    "cooldownMinutes": 30
                }
                """;

        mockMvc.perform(post("/api/conditions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("High RSI Alert"))
                .andExpect(jsonPath("$.data.indicatorType").value("RSI"))
                .andExpect(jsonPath("$.data.operator").value("GT"))
                .andExpect(jsonPath("$.data.thresholdValue").value(70))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(conditionEngine).loadRules();
    }

    @Test
    @DisplayName("GET /api/conditions returns all rules")
    void getAllRules() throws Exception {
        when(conditionRuleJpaRepository.findAll()).thenReturn(List.of(buildEntity(1L), buildEntity(2L)));

        mockMvc.perform(get("/api/conditions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/conditions/active returns only active rules")
    void getActiveRules() throws Exception {
        when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                .thenReturn(List.of(buildEntity(1L)));

        mockMvc.perform(get("/api/conditions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/conditions/{id} returns specific rule")
    void getRule() throws Exception {
        when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(buildEntity(1L)));

        mockMvc.perform(get("/api/conditions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("High RSI Alert"));
    }

    @Test
    @DisplayName("GET /api/conditions/{id} returns 404 for non-existent rule")
    void getRuleNotFound() throws Exception {
        when(conditionRuleJpaRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/conditions/999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/conditions/{id} updates a rule")
    void updateRule() throws Exception {
        ConditionRuleEntity existing = buildEntity(1L);
        when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(existing));

        ConditionRuleEntity updated = buildEntity(1L);
        updated.setName("Updated RSI Alert");
        updated.setThresholdValue(BigDecimal.valueOf(80));
        when(conditionRuleJpaRepository.save(any())).thenReturn(updated);
        doNothing().when(conditionEngine).loadRules();

        String json = """
                {
                    "name": "Updated RSI Alert",
                    "instrumentToken": 256265,
                    "tradingSymbol": "NIFTY 50",
                    "indicatorType": "RSI",
                    "indicatorPeriod": 14,
                    "operator": "GT",
                    "thresholdValue": 80,
                    "evaluationMode": "INTERVAL_5M",
                    "actionType": "ALERT_ONLY"
                }
                """;

        mockMvc.perform(put("/api/conditions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated RSI Alert"))
                .andExpect(jsonPath("$.data.thresholdValue").value(80));

        verify(conditionEngine).loadRules();
    }

    @Test
    @DisplayName("POST /api/conditions/{id}/pause pauses a rule")
    void pauseRule() throws Exception {
        ConditionRuleEntity entity = buildEntity(1L);
        when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

        ConditionRuleEntity paused = buildEntity(1L);
        paused.setStatus(ConditionRuleStatus.PAUSED);
        when(conditionRuleJpaRepository.save(any())).thenReturn(paused);
        doNothing().when(conditionEngine).loadRules();

        mockMvc.perform(post("/api/conditions/1/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        verify(conditionEngine).loadRules();
    }

    @Test
    @DisplayName("POST /api/conditions/{id}/activate activates a paused rule")
    void activateRule() throws Exception {
        ConditionRuleEntity entity = buildEntity(1L);
        entity.setStatus(ConditionRuleStatus.PAUSED);
        when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

        ConditionRuleEntity activated = buildEntity(1L);
        activated.setStatus(ConditionRuleStatus.ACTIVE);
        activated.setTriggerCount(0);
        when(conditionRuleJpaRepository.save(any())).thenReturn(activated);
        doNothing().when(conditionEngine).loadRules();

        mockMvc.perform(post("/api/conditions/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.triggerCount").value(0));

        verify(conditionEngine).loadRules();
    }

    @Test
    @DisplayName("DELETE /api/conditions/{id} deletes a rule and returns 204")
    void deleteRule() throws Exception {
        doNothing().when(conditionEngine).loadRules();

        mockMvc.perform(delete("/api/conditions/1")).andExpect(status().isNoContent());

        verify(conditionRuleJpaRepository).deleteById(1L);
        verify(conditionEngine).loadRules();
    }

    @Test
    @DisplayName("GET /api/conditions/{id}/history returns trigger history")
    void getTriggerHistory() throws Exception {
        ConditionTriggerHistoryEntity history = ConditionTriggerHistoryEntity.builder()
                .id(1L)
                .ruleId(1L)
                .ruleName("High RSI Alert")
                .indicatorType("RSI")
                .indicatorValue(BigDecimal.valueOf(75))
                .thresholdValue(BigDecimal.valueOf(70))
                .operator("GT")
                .actionTaken("ALERT_ONLY")
                .triggeredAt(LocalDateTime.of(2025, 2, 10, 10, 0))
                .build();

        when(conditionTriggerHistoryJpaRepository.findByRuleIdOrderByTriggeredAtDesc(1L))
                .thenReturn(List.of(history));

        mockMvc.perform(get("/api/conditions/1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ruleId").value(1))
                .andExpect(jsonPath("$.data[0].indicatorValue").value(75));
    }

    @Test
    @DisplayName("GET /api/conditions/history/recent returns recent triggers")
    void getRecentTriggers() throws Exception {
        when(conditionTriggerHistoryJpaRepository.findTop50ByOrderByTriggeredAtDesc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/conditions/history/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
