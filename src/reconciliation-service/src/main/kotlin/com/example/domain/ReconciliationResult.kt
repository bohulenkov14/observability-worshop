package com.example.domain

import java.math.BigDecimal
import java.time.Instant

data class ReconciliationResult(
    val userId: String,
    val currentBalance: BigDecimal,
    val expectedBalance: BigDecimal,
    val difference: BigDecimal,
    val hasDiscrepancy: Boolean,
    val checkedAt: Instant = Instant.now(),
    val transactions: List<Transaction>
) {
    companion object {
        fun create(
            userId: String,
            currentBalance: BigDecimal,
            calculatedBalance: BigDecimal,
            transactions: List<Transaction>
        ): ReconciliationResult {
            val difference = currentBalance - calculatedBalance
            return ReconciliationResult(
                userId = userId,
                currentBalance = currentBalance,
                expectedBalance = calculatedBalance,
                difference = difference,
                hasDiscrepancy = difference != BigDecimal.ZERO,
                transactions = transactions
            )
        }
    }
} 