package com.example.domain

enum class TransactionStatus {
    PENDING_FRAUD_CHECK,
    APPROVED,
    REJECTED,
    PENDING_BALANCE_DEDUCTION,
    PROCESSED
} 