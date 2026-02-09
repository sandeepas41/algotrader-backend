package com.algotrader.domain.enums;

/**
 * Classification of discrepancies found during position reconciliation
 * between our local state (Redis) and the broker's state (Kite API).
 *
 * <p>MISSING_LOCAL = broker has a position we don't know about (e.g., manual trade).
 * MISSING_BROKER = local has a position that broker doesn't (stale cache / external close).
 * PRICE_DRIFT = quantities match but average price differs by more than the threshold (2%).
 */
public enum MismatchType {
    QUANTITY_MISMATCH,
    MISSING_LOCAL,
    MISSING_BROKER,
    PRICE_DRIFT
}
