package com.example.repository

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.*

object ReconciliationRecords : UUIDTable("reconciliation_records") {
    val transactionId = varchar("transaction_id", 36)
    val userId = varchar("user_id", 36)
    val amount = decimal("amount", 19, 4)
    val description = varchar("description", 255)
    val createdAt = timestamp("created_at")
    val status = enumerationByName("status", 20, TransactionStatus::class)
    val reconciliationStatus = varchar("reconciliation_status", 20).default("PENDING")
    val lastCheckedAt = timestamp("last_checked_at").nullable()
}

class ReconciliationRepository {
    private val log = LoggerFactory.getLogger(ReconciliationRepository::class.java)

    init {
        transaction {
            SchemaUtils.create(ReconciliationRecords)
        }
    }

    fun recordTransaction(transaction: Transaction) = transaction {
        ReconciliationRecords.insert {
            it[id] = UUID.randomUUID()
            it[transactionId] = transaction.id
            it[userId] = transaction.userId
            it[amount] = transaction.amount
            it[description] = transaction.description
            it[createdAt] = transaction.createdAt
            it[status] = transaction.status
        }
    }

    fun updateTransactionStatus(transactionId: String, newStatus: TransactionStatus) = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[status] = newStatus
            it[lastCheckedAt] = Instant.now()
        }
    }

    fun findUnreconciledTransactions(): List<UnreconciledTransaction> = transaction {
        ReconciliationRecords
            .select { ReconciliationRecords.reconciliationStatus eq "PENDING" }
            .map {
                UnreconciledTransaction(
                    id = it[ReconciliationRecords.id].toString(),
                    transactionId = it[ReconciliationRecords.transactionId],
                    userId = it[ReconciliationRecords.userId],
                    amount = it[ReconciliationRecords.amount],
                    status = it[ReconciliationRecords.status],
                    createdAt = it[ReconciliationRecords.createdAt]
                )
            }
    }

    fun markAsReconciled(transactionId: String) = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[reconciliationStatus] = "RECONCILED"
            it[lastCheckedAt] = Instant.now()
        }
    }

    fun markAsDiscrepancy(transactionId: String) = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[reconciliationStatus] = "DISCREPANCY"
            it[lastCheckedAt] = Instant.now()
        }
    }
}

data class UnreconciledTransaction(
    val id: String,
    val transactionId: String,
    val userId: String,
    val amount: BigDecimal,
    val status: TransactionStatus,
    val createdAt: Instant
) 