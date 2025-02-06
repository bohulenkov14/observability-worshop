package com.example.service

import com.example.domain.Transaction
import com.example.repository.TransactionRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class TransactionService(private val transactionRepository: TransactionRepository) {
    private val log = LoggerFactory.getLogger(TransactionService::class.java)

    fun createTransaction(userId: String, amount: BigDecimal, description: String): Transaction {
        log.info("Creating transaction - user_id: {}, amount: {}", userId, amount)
        return transactionRepository.create(userId, amount, description)
    }

    fun getUserTransactions(userId: String): List<Transaction> {
        log.info("Fetching transactions for user_id: {}", userId)
        return transactionRepository.findByUserId(userId)
    }
} 