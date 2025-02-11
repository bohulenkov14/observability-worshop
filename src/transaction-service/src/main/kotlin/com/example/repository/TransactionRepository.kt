package com.example.repository

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.domain.TransactionType
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object Transactions : UUIDTable("transactions") {
    val userId = varchar("user_id", 36)
    val amount = decimal("amount", 19, 4)
    val description = varchar("description", 255)
    val type = enumerationByName("type", 20, TransactionType::class)
    val createdAt = timestamp("created_at")
    val status = enumerationByName("status", 30, TransactionStatus::class)
}

class TransactionRepository {
    private val log = LoggerFactory.getLogger(TransactionRepository::class.java)
    private val transactions = ConcurrentHashMap<String, Transaction>()

    init {
        transaction {
            SchemaUtils.create(Transactions)
        }
    }

    fun create(
        userId: String,
        amount: BigDecimal,
        description: String,
        type: TransactionType
    ): Transaction {
        val transaction = Transaction(
            id = UUID.randomUUID().toString(),
            userId = userId,
            amount = amount,
            description = description,
            status = TransactionStatus.PENDING_FRAUD_CHECK,
            type = type,
            createdAt = Instant.now()
        )
        transactions[transaction.id] = transaction
        return transaction
    }

    fun updateStatus(transactionId: String, newStatus: TransactionStatus): Transaction? {
        return transactions[transactionId]?.let { transaction ->
            val updatedTransaction = transaction.copy(status = newStatus)
            transactions[transactionId] = updatedTransaction
            updatedTransaction
        }
    }

    fun findByUserId(userId: String): List<Transaction> {
        return transactions.values.filter { it.userId == userId }
    }

    fun findById(id: String): Transaction? {
        return transactions[id]
    }

    private fun ResultRow.toTransaction() = Transaction(
        id = this[Transactions.id].toString(),
        userId = this[Transactions.userId],
        amount = this[Transactions.amount],
        description = this[Transactions.description],
        type = this[Transactions.type],
        createdAt = this[Transactions.createdAt],
        status = this[Transactions.status]
    )
} 