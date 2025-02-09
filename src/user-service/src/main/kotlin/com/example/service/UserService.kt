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

    fun updateBalance(userId: String, newBalance: BigDecimal): User? {
        log.info("Updating balance - user_id: {}, new_balance: {}", userId, newBalance)
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