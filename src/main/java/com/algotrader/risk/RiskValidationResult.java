package com.algotrader.risk;

import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * Result of pre-trade risk validation for an order.
 *
 * <p>Either APPROVED (empty violations list) or REJECTED (one or more violations).
 * The OrderRouter checks this before allowing an order into the execution pipeline.
 *
 * <p>When rejected, all violations are included so the trader sees the complete
 * picture (not just the first failure).
 */
@Getter
public class RiskValidationResult {

    private final boolean approved;
    private final List<RiskViolation> violations;

    private RiskValidationResult(boolean approved, List<RiskViolation> violations) {
        this.approved = approved;
        this.violations = violations;
    }

    public static RiskValidationResult approved() {
        return new RiskValidationResult(true, Collections.emptyList());
    }

    public static RiskValidationResult rejected(List<RiskViolation> violations) {
        return new RiskValidationResult(false, List.copyOf(violations));
    }

    public boolean isRejected() {
        return !approved;
    }
}
