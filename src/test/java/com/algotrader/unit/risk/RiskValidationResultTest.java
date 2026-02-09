package com.algotrader.unit.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.risk.RiskValidationResult;
import com.algotrader.risk.RiskViolation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RiskValidationResult and RiskViolation value objects.
 */
class RiskValidationResultTest {

    @Test
    @DisplayName("Approved result has no violations")
    void approved_noViolations() {
        RiskValidationResult result = RiskValidationResult.approved();

        assertThat(result.isApproved()).isTrue();
        assertThat(result.isRejected()).isFalse();
        assertThat(result.getViolations()).isEmpty();
    }

    @Test
    @DisplayName("Rejected result carries all violations")
    void rejected_carriesViolations() {
        List<RiskViolation> violations =
                List.of(RiskViolation.of("CODE_1", "message 1"), RiskViolation.of("CODE_2", "message 2"));

        RiskValidationResult result = RiskValidationResult.rejected(violations);

        assertThat(result.isApproved()).isFalse();
        assertThat(result.isRejected()).isTrue();
        assertThat(result.getViolations()).hasSize(2);
    }

    @Test
    @DisplayName("Rejected violations list is immutable copy")
    void rejected_immutableCopy() {
        List<RiskViolation> violations = new java.util.ArrayList<>();
        violations.add(RiskViolation.of("CODE_1", "message"));

        RiskValidationResult result = RiskValidationResult.rejected(violations);
        violations.add(RiskViolation.of("CODE_2", "extra"));

        // Original list was copied; adding to source doesn't affect result
        assertThat(result.getViolations()).hasSize(1);
    }

    @Test
    @DisplayName("RiskViolation toString returns code: message")
    void violationToString() {
        RiskViolation violation = RiskViolation.of("LIMIT_EXCEEDED", "order too large");

        assertThat(violation.toString()).isEqualTo("LIMIT_EXCEEDED: order too large");
    }
}
