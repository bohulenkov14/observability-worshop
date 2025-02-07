package com.example.service

import com.example.repository.ReconciliationRepository
import com.example.repository.UnreconciledTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.fixedRateTimer

class ReconciliationService(
    private val reconciliationRepository: ReconciliationRepository
) {
    private val log = LoggerFactory.getLogger(ReconciliationService::class.java)

    init {
        startReconciliationJob()
    }

    private fun startReconciliationJob() {
        fixedRateTimer(
            name = "reconciliation-job",
            initialDelay = Duration.ofSeconds(30).toMillis(),
            period = Duration.ofMinutes(1).toMillis()
        ) {
            try {
                performReconciliation()
            } catch (e: Exception) {
                log.error("Error during reconciliation: {}", e.message, e)
            }
        }
    }

    private fun performReconciliation() {
        log.info("Starting reconciliation job")
        val unreconciledTransactions = reconciliationRepository.findUnreconciledTransactions()
        
        if (unreconciledTransactions.isEmpty()) {
            log.info("No unreconciled transactions found")
            return
        }

        log.info("Found {} unreconciled transactions", unreconciledTransactions.size)
        
        unreconciledTransactions.forEach { transaction ->
            try {
                reconcileTransaction(transaction)
            } catch (e: Exception) {
                log.error("Error reconciling transaction {}: {}", transaction.transactionId, e.message, e)
            }
        }
    }

    private fun reconcileTransaction(transaction: UnreconciledTransaction) {
        // Skip recent transactions (less than 5 minutes old) to allow for processing time
        if (Duration.between(transaction.createdAt, Instant.now()).toMinutes() < 5) {
            log.debug("Skipping recent transaction {}", transaction.transactionId)
            return
        }

        log.info("Reconciling transaction {}", transaction.transactionId)

        // For demonstration purposes, we'll mark transactions as reconciled after 5 minutes
        // In a real system, you would implement more sophisticated reconciliation logic
        reconciliationRepository.markAsReconciled(transaction.transactionId)
        log.info("Transaction {} reconciled successfully", transaction.transactionId)
    }
} 