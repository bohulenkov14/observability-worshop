package com.example.repository

import com.example.domain.User
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.util.*

object Users : UUIDTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val balance = decimal("balance", 19, 4)
    val isFrozen = bool("is_frozen").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

class UserRepository {
    init {
        transaction {
            SchemaUtils.create(Users)
        }
    }

    fun create(username: String, email: String): User = transaction {
        val id = UUID.randomUUID()
        val now = Instant.now()
        
        Users.insert {
            it[Users.id] = id
            it[Users.username] = username
            it[Users.email] = email
            it[balance] = BigDecimal.ZERO
            it[isFrozen] = false
            it[createdAt] = now
            it[updatedAt] = now
        }

        User(
            id = id.toString(),
            username = username,
            email = email,
            balance = BigDecimal.ZERO,
            isFrozen = false,
            createdAt = now,
            updatedAt = now
        )
    }

    fun findById(id: String): User? = transaction {
        Users.select { Users.id eq UUID.fromString(id) }
            .singleOrNull()
            ?.toUser()
    }

    fun updateBalance(id: String, newBalance: BigDecimal): User? = transaction {
        val now = Instant.now()
        
        Users.update({ Users.id eq UUID.fromString(id) }) {
            it[balance] = newBalance
            it[updatedAt] = now
        }

        findById(id)
    }

    fun setFrozenStatus(id: String, frozen: Boolean): User? = transaction {
        val now = Instant.now()
        
        Users.update({ Users.id eq UUID.fromString(id) }) {
            it[isFrozen] = frozen
            it[updatedAt] = now
        }

        findById(id)
    }

    private fun ResultRow.toUser() = User(
        id = this[Users.id].toString(),
        username = this[Users.username],
        email = this[Users.email],
        balance = this[Users.balance],
        isFrozen = this[Users.isFrozen],
        createdAt = this[Users.createdAt],
        updatedAt = this[Users.updatedAt]
    )
} 