package com.example.service

import com.example.repository.ReconciliationRepository
import com.example.repository.UnreconciledTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.fixedRateTimer
import org.http4k.core.*
import org.http4k.client.OkHttp
import org.http4k.format.Jackson.auto
import java.math.BigDecimal
import com.example.domain.TransactionStatus
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import com.example.domain.TransactionType

class ReconciliationService(
    private val reconciliationRepository: ReconciliationRepository
) {
    private val log = LoggerFactory.getLogger(ReconciliationService::class.java)
    private val userServiceClient = OkHttp()
    private val userBalanceLens = Body.auto<ApiResponse<UserBalance>>().toLens()
    private val freezeAccountLens = Body.auto<FreezeAccountRequest>().toLens()
    private val tracer = GlobalOpenTelemetry.getTracer("reconciliation-service")

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
        val span = tracer.spanBuilder("performReconciliation")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        
        try {
            log.info("Starting reconciliation job")
            val unreconciledTransactions = reconciliationRepository.findUnreconciledTransactions()
            
            if (unreconciledTransactions.isEmpty()) {
                log.info("No unreconciled transactions found")
                return
            }

            span.setAttribute(AttributeKey.longKey("unreconciled.transactions.count"), unreconciledTransactions.size.toLong())
            log.info("Found {} unreconciled transactions", unreconciledTransactions.size)
            
            // Group transactions by user ID
            val transactionsByUser = unreconciledTransactions.groupBy { it.userId }
            span.setAttribute(AttributeKey.longKey("affected.users.count"), transactionsByUser.size.toLong())
            
            transactionsByUser.forEach { (userId, transactions) ->
                try {
                    reconcileUserTransactions(userId, transactions)
                } catch (e: Exception) {
                    log.error("Error reconciling transactions for user {}: {}", userId, e.message, e)
                    span.recordException(e)
                }
            }
            span.setStatus(StatusCode.OK)
        } catch (e: Exception) {
            log.error("Error during reconciliation: {}", e.message, e)
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Reconciliation failed")
        } finally {
            span.end()
        }
    }

    private fun reconcileUserTransactions(userId: String, transactions: List<UnreconciledTransaction>) {
        val span = tracer.spanBuilder("reconcileUserTransactions")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        
        try {
            span.setAttribute(AttributeKey.stringKey("user.id"), userId)
            span.setAttribute(AttributeKey.longKey("transactions.count"), transactions.size.toLong())

            // Skip recent transactions (less than 5 minutes old) to allow for processing time
            val oldTransactions = transactions.filter { 
                Duration.between(it.createdAt, Instant.now()).toMinutes() >= 5 
            }
            
            if (oldTransactions.isEmpty()) {
                log.debug("No old enough transactions to reconcile for user {}", userId)
                return
            }

            span.setAttribute(AttributeKey.longKey("old.transactions.count"), oldTransactions.size.toLong())

            // Get current user balance
            val currentBalance = getUserBalance(userId)
            if (currentBalance == null) {
                log.error("Could not fetch balance for user {}", userId)
                span.setStatus(StatusCode.ERROR, "Could not fetch user balance")
                return
            }

            // Calculate expected balance from processed transactions
            val expectedBalance = calculateExpectedBalance(oldTransactions)
            
            span.setAttribute(AttributeKey.stringKey("current.balance"), currentBalance.toString())
            span.setAttribute(AttributeKey.stringKey("expected.balance"), expectedBalance.toString())
            
            // Compare balances
            if (currentBalance.compareTo(expectedBalance) != 0) {
                val difference = currentBalance - expectedBalance
                span.setAttribute(AttributeKey.stringKey("balance.difference"), difference.toString())
                span.setAttribute(AttributeKey.booleanKey("has.discrepancy"), true)
                
                // Add detailed event about the mismatch
                span.addEvent("balance_mismatch_detected", Attributes.builder()
                    .put("user.id", userId)
                    .put("current.balance", currentBalance.toString())
                    .put("expected.balance", expectedBalance.toString())
                    .put("difference", difference.toString())
                    .put("transactions.count", oldTransactions.size.toLong())
                    .build()
                )

                // Add transaction details to the event
                oldTransactions.forEachIndexed { index, tx ->
                    span.addEvent("transaction_details", Attributes.builder()
                        .put("transaction.${index}.id", tx.transactionId)
                        .put("transaction.${index}.type", tx.type.name)
                        .put("transaction.${index}.amount", tx.amount.toString())
                        .put("transaction.${index}.status", tx.status.name)
                        .build()
                    )
                }
                
                log.warn(
                    "Balance mismatch for user {} - Current: {}, Expected: {}, Difference: {}", 
                    userId, 
                    currentBalance, 
                    expectedBalance,
                    difference
                )
                oldTransactions.forEach { transaction ->
                    reconciliationRepository.markAsDiscrepancy(transaction.transactionId)
                }
                // Freeze the user account when discrepancy is detected
                freezeUserAccount(userId)
            } else {
                span.setAttribute(AttributeKey.booleanKey("has.discrepancy"), false)
                span.addEvent("balance_reconciled_successfully", Attributes.builder()
                    .put("user.id", userId)
                    .put("balance", currentBalance.toString())
                    .put("transactions.count", oldTransactions.size.toLong())
                    .build()
                )
                log.info("Balance reconciled successfully for user {}", userId)
                oldTransactions.forEach { transaction ->
                    reconciliationRepository.markAsReconciled(transaction.transactionId)
                }
            }
            span.setStatus(StatusCode.OK)
        } catch (e: Exception) {
            log.error("Error reconciling user transactions: {}", e.message, e)
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Reconciliation failed")
        } finally {
            span.end()
        }
    }

    private fun getUserBalance(userId: String): BigDecimal? {
        return try {
            val response = userServiceClient(
                Request(Method.GET, "http://user-service:8080/user/balance/$userId")
            )

            if (response.status != Status.OK) {
                log.error("Failed to get user balance. Status: {}", response.status)
                return null
            }

            userBalanceLens(response).data?.balance
        } catch (e: Exception) {
            log.error("Error fetching user balance: {}", e.message, e)
            null
        }
    }

    private fun calculateExpectedBalance(transactions: List<UnreconciledTransaction>): BigDecimal {
        return transactions
            .filter { it.status == TransactionStatus.PROCESSED }
            .fold(BigDecimal.ZERO) { acc, transaction ->
                when (transaction.type) {
                    TransactionType.TOP_UP -> acc + transaction.amount
                    TransactionType.PURCHASE -> acc - transaction.amount
                }
            }
    }

    private fun getUserTopUps(userId: String): BigDecimal? {
        return try {
            val response = userServiceClient(
                Request(Method.GET, "http://user-service:8080/user/topups/$userId")
            )

            if (response.status != Status.OK) {
                log.error("Failed to get user top-ups. Status: {}", response.status)
                return null
            }

            val topUpsLens = Body.auto<ApiResponse<UserTopUps>>().toLens()
            topUpsLens(response).data?.totalAmount
        } catch (e: Exception) {
            log.error("Error fetching user top-ups: {}", e.message, e)
            null
        }
    }

    private fun freezeUserAccount(userId: String) {
        try {
            val freezeRequest = FreezeAccountRequest(userId)
            val response = userServiceClient(
                Request(Method.POST, "http://user-service:8080/user/freeze")
                    .with(freezeAccountLens of freezeRequest)
            )

            when (response.status) {
                Status.OK -> {
                    log.info("Successfully froze account for user {} due to balance discrepancy", userId)
                }
                Status.NOT_FOUND -> {
                    log.error("Could not freeze account - user {} not found", userId)
                }
                else -> {
                    log.error("Failed to freeze account for user {}. Status: {}", userId, response.status)
                }
            }
        } catch (e: Exception) {
            log.error("Error freezing user account {}: {}", userId, e.message, e)
        }
    }
}

data class ApiResponse<T>(
    val status: String,
    val data: T? = null,
    val message: String? = null
)

data class UserBalance(
    val userId: String,
    val balance: BigDecimal
)

data class FreezeAccountRequest(val userId: String)

data class UserTopUps(
    val userId: String,
    val totalAmount: BigDecimal
) 