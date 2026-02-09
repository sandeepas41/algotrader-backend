package com.algotrader.domain.enums;

/**
 * Status of an execution journal entry (WAL for multi-leg operations).
 * REQUIRES_RECOVERY means the app crashed mid-execution — StartupRecoveryService
 * checks for these on boot and resolves them (complete remaining legs or rollback).
 */
public enum JournalStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    REQUIRES_RECOVERY
}
