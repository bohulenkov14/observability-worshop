package com.example.domain

import java.math.BigDecimal
import java.time.Instant

enum class TransactionStatus {
    PENDING_FRAUD_CHECK,
    APPROVED,
    REJECTED
}

data class Transaction(
    val id: String,
    val userId: String,
    val amount: BigDecimal,
    val description: String,
    val createdAt: Instant,
    val status: TransactionStatus
) 