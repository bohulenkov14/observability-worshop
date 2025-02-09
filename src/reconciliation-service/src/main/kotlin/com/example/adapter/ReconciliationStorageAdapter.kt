package com.example.adapter

import com.example.application.port.ReconciliationStoragePort
import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.domain.TransactionType
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

object ReconciliationRecords : UUIDTable("reconciliation_records") {
    val transactionId = varchar("transaction_id", 36)
    val userId = varchar("user_id", 36)
    val amount = decimal("amount", 19, 4)
    val description = varchar("description", 255)
    val createdAt = timestamp("created_at")
    val status = enumerationByName("status", 50, TransactionStatus::class)
    val reconciliationStatus = varchar("reconciliation_status", 50).default("PENDING")
    val lastCheckedAt = timestamp("last_checked_at").nullable()
    val type = enumerationByName("type", 50, TransactionType::class)
    val reconciled = bool("reconciled").default(false)
    val isDiscrepancy = bool("is_discrepancy").default(false)
}

class ReconciliationStorageAdapter : ReconciliationStoragePort {
    private val log = LoggerFactory.getLogger(ReconciliationStorageAdapter::class.java)

    init {
        transaction {
            SchemaUtils.create(ReconciliationRecords)
        }
    }

    override fun recordTransaction(transaction: Transaction) = transaction {
        ReconciliationRecords.insert {
            it[id] = UUID.randomUUID()
            it[transactionId] = transaction.id
            it[userId] = transaction.userId
            it[amount] = transaction.amount
            it[description] = transaction.description
            it[createdAt] = transaction.createdAt
            it[status] = transaction.status
            it[type] = transaction.type
            it[reconciled] = false
            it[isDiscrepancy] = false
        }
        log.info(
            "Recorded transaction for reconciliation - ID: {}, Type: {}, Amount: {}, Status: {}", 
            transaction.id,
            transaction.type,
            transaction.amount,
            transaction.status
        )
    }

    override fun updateTransactionStatus(transactionId: String, newStatus: TransactionStatus): Unit = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[status] = newStatus
            it[lastCheckedAt] = Instant.now()
        }
    }

    override fun findUnreconciledTransactions(): List<Transaction> = transaction {
        ReconciliationRecords
            .select { (ReconciliationRecords.reconciled eq false) and (ReconciliationRecords.isDiscrepancy eq false) }
            .map {
                Transaction(
                    id = it[ReconciliationRecords.transactionId],
                    userId = it[ReconciliationRecords.userId],
                    amount = it[ReconciliationRecords.amount],
                    description = it[ReconciliationRecords.description],
                    createdAt = it[ReconciliationRecords.createdAt],
                    status = it[ReconciliationRecords.status],
                    type = it[ReconciliationRecords.type]
                )
            }
    }

    override fun markAsReconciled(transactionId: String): Unit = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[reconciliationStatus] = "RECONCILED"
            it[reconciled] = true
            it[lastCheckedAt] = Instant.now()
        }
    }

    override fun markAsDiscrepancy(transactionId: String): Unit = transaction {
        ReconciliationRecords.update({ ReconciliationRecords.transactionId eq transactionId }) {
            it[reconciliationStatus] = "DISCREPANCY"
            it[isDiscrepancy] = true
            it[lastCheckedAt] = Instant.now()
        }
    }
} 