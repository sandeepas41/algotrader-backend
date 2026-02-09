package com.algotrader.domain.vo;

import java.math.BigDecimal;
import lombok.Value;

/**
 * Immutable value object representing a monetary amount with currency.
 * All trading P&L, charges, and margin values use INR.
 * Arithmetic operations return new instances (immutable).
 */
@Value
public class Money {

    BigDecimal amount;
    String currency;

    public static final String INR = "INR";

    public static Money inr(BigDecimal amount) {
        return new Money(amount, INR);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, INR);
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal multiplier) {
        return new Money(amount.multiply(multiplier), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }
}
