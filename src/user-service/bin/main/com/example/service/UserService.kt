package com.example.service

import com.example.domain.User
import com.example.repository.UserRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class AccountFrozenException(userId: String) : RuntimeException("Account is frozen for user: $userId")

class UserService(private val userRepository: UserRepository) {
    private val log = LoggerFactory.getLogger(UserService::class.java)

    fun createUser(username: String, email: String): User {
        log.info("Creating user - username: {}, email: {}", username, email)
        return userRepository.create(username, email)
    }

    fun topUpBalance(userId: String, amount: BigDecimal): User? {
        log.info("Processing top-up - user_id: {}, amount: {}", userId, amount)
        
        val user = userRepository.findById(userId) ?: run {
            log.error("User not found - user_id: {}", userId)
            return null
        }

        val newBalance = user.balance + amount
        return userRepository.updateBalance(userId, newBalance)
    }

    fun deductBalance(userId: String, amount: BigDecimal): User? {
        log.info("Processing deduction - user_id: {}, amount: {}", userId, amount)
        
        val user = userRepository.findById(userId) ?: run {
            log.error("User not found - user_id: {}", userId)
            return null
        }

        if (user.isFrozen) {
            log.error("Cannot deduct from frozen account - user_id: {}", userId)
            throw AccountFrozenException(userId)
        }

        val newBalance = user.balance - amount
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
} 