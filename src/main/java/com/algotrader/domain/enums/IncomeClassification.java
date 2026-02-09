package com.algotrader.domain.enums;

/**
 * Indian income tax classification for trading income.
 *
 * <p>F&O (Futures & Options) income is classified as non-speculative business income
 * under Section 43(5) of the Income Tax Act. Intraday equity (cash segment) trades
 * are classified as speculative business income.
 */
public enum IncomeClassification {
    /** F&O income: classified as non-speculative business income. */
    NON_SPECULATIVE_BUSINESS,

    /** Intraday equity: classified as speculative business income. */
    SPECULATIVE_BUSINESS
}
