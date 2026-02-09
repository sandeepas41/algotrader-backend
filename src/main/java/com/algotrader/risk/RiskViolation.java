package com.algotrader.risk;

import lombok.Builder;
import lombok.Getter;

/**
 * Represents a single risk limit violation detected during pre-trade validation.
 *
 * <p>Each violation has a code (machine-readable) and a message (human-readable).
 * The code follows the pattern: POSITION_SIZE_EXCEEDED, DAILY_LOSS_LIMIT_BREACHED,
 * UNDERLYING_LOT_LIMIT_EXCEEDED, etc.
 *
 * <p>Multiple violations can be returned in a single validation to give the trader
 * a complete picture of why the order was rejected.
 */
@Getter
@Builder
public class RiskViolation {

    /** Machine-readable violation code (e.g., "POSITION_SIZE_EXCEEDED"). */
    private final String code;

    /** Human-readable description of the violation. */
    private final String message;

    public static RiskViolation of(String code, String message) {
        return RiskViolation.builder().code(code).message(message).build();
    }

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
