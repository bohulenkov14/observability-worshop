package com.example.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant

enum class TransactionType {
    TOP_UP,
    PURCHASE
}

enum class TransactionStatus {
    PENDING_FRAUD_CHECK,
    APPROVED,
    REJECTED,
    PENDING_BALANCE_DEDUCTION,
    PROCESSED
}

data class Transaction @JsonCreator constructor(
    @JsonProperty("id") val id: String,
    @JsonProperty("userId") val userId: String,
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("description") val description: String,
    @JsonProperty("createdAt") val createdAt: Instant,
    @JsonProperty("status") val status: TransactionStatus,
    @JsonProperty("type") val type: TransactionType
) 