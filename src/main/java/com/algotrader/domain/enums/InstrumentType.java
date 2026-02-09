package com.algotrader.domain.enums;

/**
 * Type of tradeable instrument on NSE/NFO exchanges.
 * Maps to Kite API's instrument_type field in the instruments dump.
 */
public enum InstrumentType {
    EQ,
    FUT,
    CE,
    PE
}
