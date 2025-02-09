package com.example.domain

import java.math.BigDecimal
import java.time.Instant

enum class TransactionType {
    TOP_UP,
    PURCHASE
}

enum class TransactionStatus {
    PENDING_FRAUD_CHECK,
    PENDING_BALANCE_DEDUCTION,
    PROCESSED,
    REJECTED
}

data class Transaction(
    val id: String,
    val userId: String,
    val amount: BigDecimal,
    val description: String,
    val status: TransactionStatus,
    val type: TransactionType,
    val createdAt: Instant = Instant.now()
) 