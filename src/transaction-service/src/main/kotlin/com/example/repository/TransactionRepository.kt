package com.example.repository

import com.example.domain.Transaction
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.util.*

object Transactions : UUIDTable("transactions") {
    val userId = varchar("user_id", 36)
    val amount = decimal("amount", 19, 4)
    val description = varchar("description", 255)
    val createdAt = timestamp("created_at")
}

class TransactionRepository {
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
        }

        Transaction(
            id = id.toString(),
            userId = userId,
            amount = amount,
            description = description,
            createdAt = now
        )
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
        createdAt = this[Transactions.createdAt]
    )
} 