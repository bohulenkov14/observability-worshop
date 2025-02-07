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

object Transactions : UUIDTable("transactions") {
    val userId = varchar("user_id", 36)
    val amount = decimal("amount", 19, 4)
    val description = varchar("description", 255)
    val createdAt = timestamp("created_at")
    val status = enumerationByName("status", 20, TransactionStatus::class)
}

class TransactionRepository {
    private val log = LoggerFactory.getLogger(TransactionRepository::class.java)

    init {
        transaction {
            SchemaUtils.create(Transactions)
        }
    }

    fun create(userId: String, amount: BigDecimal, description: String): Transaction = transaction {
        val id = UUID.randomUUID()
        val now = Instant.now()
        
        Transactions.insert {
            it[Transactions.id] = id
            it[Transactions.userId] = userId
            it[Transactions.amount] = amount
            it[Transactions.description] = description
            it[createdAt] = now
            it[status] = TransactionStatus.PENDING_FRAUD_CHECK
        }

        Transaction(
            id = id.toString(),
            userId = userId,
            amount = amount,
            description = description,
            createdAt = now,
            status = TransactionStatus.PENDING_FRAUD_CHECK
        )
    }

    fun updateStatus(transactionId: String, newStatus: TransactionStatus): Transaction? = transaction {
        val uuid = UUID.fromString(transactionId)
        Transactions.update({ Transactions.id eq uuid }) {
            it[status] = newStatus
        }
        
        Transactions
            .select { Transactions.id eq uuid }
            .map { it.toTransaction() }
            .singleOrNull()
    }

    fun findByUserId(userId: String): List<Transaction> = transaction {
        Transactions
            .select { Transactions.userId eq userId }
            .map { it.toTransaction() }
    }

    private fun ResultRow.toTransaction() = Transaction(
        id = this[Transactions.id].toString(),
        userId = this[Transactions.userId],
        amount = this[Transactions.amount],
        description = this[Transactions.description],
        createdAt = this[Transactions.createdAt],
        status = this[Transactions.status]
    )
} 