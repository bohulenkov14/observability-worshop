package com.example.application.port

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import java.math.BigDecimal

interface UserBalancePort {
    fun getUserBalance(userId: String): BigDecimal?
    fun freezeUserAccount(userId: String): Boolean
}

interface ReconciliationStoragePort {
    fun findUnreconciledTransactions(): List<Transaction>
    fun recordTransaction(transaction: Transaction)
    fun updateTransactionStatus(transactionId: String, newStatus: TransactionStatus)
    fun markAsReconciled(transactionId: String)
    fun markAsDiscrepancy(transactionId: String)
} 