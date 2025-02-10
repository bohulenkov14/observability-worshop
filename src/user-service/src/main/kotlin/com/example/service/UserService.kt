package com.example.service

import com.example.domain.User
import com.example.repository.UserRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

class AccountFrozenException(userId: String) : RuntimeException("Account is frozen for user: $userId")

class UserService(private val userRepository: UserRepository) {
    private val log = LoggerFactory.getLogger(UserService::class.java)
    private val meter = GlobalOpenTelemetry.getMeter("user-service")

    // Balance Change Metrics
    private val balanceChanges = meter.histogramBuilder("balance_changes")
        .setDescription("Distribution of balance changes")
        .setExplicitBucketBoundariesAdvice(
            listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)
        )
        .build()

    fun createUser(username: String, email: String): User {
        log.info("Creating user - username: {}, email: {}", username, email)
        return userRepository.create(username, email)
    }

    fun updateBalance(userId: String, newBalance: BigDecimal): User? {
        log.info("Updating balance - user_id: {}, new_balance: {}", userId, newBalance)
        val user = userRepository.findById(userId)
        
        if (user != null) {
            val balanceDelta = newBalance - user.balance
            balanceChanges.record(balanceDelta.abs().toDouble(), Attributes.of(
                AttributeKey.stringKey("user_id"), userId,
                AttributeKey.stringKey("operation_type"), if (balanceDelta >= BigDecimal.ZERO) "credit" else "debit"
            ))
        }
        
        return userRepository.updateBalance(userId, newBalance)
    }

    fun freezeAccount(userId: String): User? {
        log.info("Freezing account - user_id: {}", userId)
        return userRepository.setFrozenStatus(userId, true)
    }

    fun unfreezeAccount(userId: String): User? {
        log.info("Unfreezing account - user_id: {}", userId)
        return userRepository.setFrozenStatus(userId, false)
    }

    fun findUser(userId: String): User? {
        log.info("Finding user - user_id: {}", userId)
        return userRepository.findById(userId)
    }
} 