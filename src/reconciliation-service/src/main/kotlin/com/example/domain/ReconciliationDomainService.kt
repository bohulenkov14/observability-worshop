package com.example.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class ReconciliationDomainService {
    fun calculateExpectedBalance(transactions: List<Transaction>): BigDecimal {
        return transactions
            .filter { it.status == TransactionStatus.PROCESSED }
            .fold(BigDecimal.ZERO) { acc, transaction ->
                when (transaction.type) {
                    TransactionType.TOP_UP -> acc + transaction.amount
                    TransactionType.PURCHASE -> acc - transaction.amount
                }
            }
    }

    fun filterOldTransactions(transactions: List<Transaction>, minAge: Duration = Duration.ofMinutes(5)): List<Transaction> {
        return transactions.filter { 
            Duration.between(it.createdAt, Instant.now()) >= minAge
        }
    }

    fun reconcileBalance(
        userId: String,
        currentBalance: BigDecimal,
        transactions: List<Transaction>
    ): ReconciliationResult {
        val oldTransactions = filterOldTransactions(transactions)
        val expectedBalance = calculateExpectedBalance(oldTransactions)
        
        return ReconciliationResult.create(
            userId = userId,
            currentBalance = currentBalance,
            calculatedBalance = expectedBalance,
            transactions = oldTransactions
        )
    }
} 