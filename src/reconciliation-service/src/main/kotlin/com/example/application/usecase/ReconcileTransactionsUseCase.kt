package com.example.application.usecase

import com.example.application.port.ReconciliationStoragePort
import com.example.application.port.UserBalancePort
import com.example.domain.ReconciliationDomainService
import com.example.domain.ReconciliationResult
import com.example.domain.Transaction
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.LoggerFactory

class ReconcileTransactionsUseCase(
    private val reconciliationDomainService: ReconciliationDomainService,
    private val userBalancePort: UserBalancePort,
    private val reconciliationStoragePort: ReconciliationStoragePort,
    private val tracer: Tracer
) {
    private val log = LoggerFactory.getLogger(ReconcileTransactionsUseCase::class.java)

    fun execute() {
        val span = Span.current()
        log.info("Starting reconciliation job")
        val unreconciledTransactions = reconciliationStoragePort.findUnreconciledTransactions()
        
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
            reconcileUserTransactions(userId, transactions)
        }
    }

    @WithSpan("reconcileUserTransactions")
    private fun reconcileUserTransactions(
        @SpanAttribute("user.id") userId: String,
        transactions: List<Transaction>
    ) {
        val span = Span.current()
        span.setAttribute(AttributeKey.longKey("transactions.count"), transactions.size.toLong())

        val currentBalance = userBalancePort.getUserBalance(userId)
        if (currentBalance == null) {
            log.error("Could not fetch balance for user {}", userId)
            span.setStatus(StatusCode.ERROR, "Could not fetch user balance")
            return
        }

        val reconciliationResult = reconciliationDomainService.reconcileBalance(
            userId = userId,
            currentBalance = currentBalance,
            transactions = transactions
        )
    
        span.addEvent("balance_checked", Attributes.builder()
            .put("reconciliationResult.currentBalance", reconciliationResult.currentBalance.toString())
            .put("reconciliationResult.expectedBalance", reconciliationResult.expectedBalance.toString())
            .put("reconciliationResult.hasDiscrepancy", reconciliationResult.hasDiscrepancy.toString())
            .build()
        )
        
        if (reconciliationResult.hasDiscrepancy) {
            handleDiscrepancy(reconciliationResult, span)
        } else {
            handleSuccessfulReconciliation(reconciliationResult, span)
        }
    }

    private fun handleDiscrepancy(result: ReconciliationResult, span: Span) {
        span.setAttribute(AttributeKey.stringKey("balance.difference"), result.difference.toString())
        
        // Add transaction details to the event
        result.transactions.forEachIndexed { index, tx ->
            span.addEvent("transaction_details", Attributes.builder()
                .put("transaction.${index}.id", tx.id)
                .put("transaction.${index}.type", tx.type.name)
                .put("transaction.${index}.amount", tx.amount.toString())
                .put("transaction.${index}.status", tx.status.name)
                .build()
            )
        }
        
        log.warn(
            "Balance mismatch for user {} - Current: {}, Expected: {}, Difference: {}", 
            result.userId, 
            result.currentBalance, 
            result.expectedBalance,
            result.difference
        )

        result.transactions.forEach { transaction ->
            reconciliationStoragePort.markAsDiscrepancy(transaction.id)
        }

        userBalancePort.freezeUserAccount(result.userId)
    }

    private fun handleSuccessfulReconciliation(result: ReconciliationResult, span: Span) {
        result.transactions.forEach { transaction ->
            reconciliationStoragePort.markAsReconciled(transaction.id)
        }
        log.info("Balance reconciled successfully for user {}", result.userId)
        span.addEvent("balance_reconciled_successfully", Attributes.builder()
            .put("user.id", result.userId)
            .put("balance", result.currentBalance.toString())
            .put("transactions.count", result.transactions.size.toLong())
            .build()
        )
    }
} 