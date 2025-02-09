package com.example

import com.example.adapter.KafkaTransactionConsumer
import com.example.adapter.ReconciliationStorageAdapter
import com.example.adapter.UserBalanceAdapter
import com.example.application.usecase.ReconcileTransactionsUseCase
import com.example.config.AppConfig
import com.example.domain.ReconciliationDomainService
import com.sksamuel.hoplite.ConfigLoader
import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import kotlin.concurrent.fixedRateTimer
import java.time.Duration

private val log = LoggerFactory.getLogger("ReconciliationService")

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    val tracer = GlobalOpenTelemetry.getTracer("reconciliation-service")
    
    // Connect to database
    Database.connect(
        url = config.database.jdbcUrl,
        user = config.database.user,
        password = config.database.password,
        driver = "org.postgresql.Driver"
    )

    // Initialize adapters
    val reconciliationStorageAdapter = ReconciliationStorageAdapter()
    val userBalanceAdapter = UserBalanceAdapter()

    // Initialize domain service
    val reconciliationDomainService = ReconciliationDomainService()

    // Initialize use case
    val reconcileTransactionsUseCase = ReconcileTransactionsUseCase(
        reconciliationDomainService = reconciliationDomainService,
        userBalancePort = userBalanceAdapter,
        reconciliationStoragePort = reconciliationStorageAdapter,
        tracer = tracer
    )

    // Initialize Kafka consumer
    val kafkaConsumer = KafkaTransactionConsumer(
        reconciliationStoragePort = reconciliationStorageAdapter,
        bootstrapServers = config.kafka.bootstrapServers,
        groupId = config.kafka.groupId
    )

    // Start reconciliation job
    fixedRateTimer(
        name = "reconciliation-job",
        initialDelay = Duration.ofSeconds(30).toMillis(),
        period = Duration.ofMinutes(1).toMillis()
    ) {
        val span = tracer.spanBuilder("reconciliation-job").startSpan()
        try {
            span.makeCurrent().use {
                reconcileTransactionsUseCase.execute()
            }
        } catch (e: Exception) {
            log.error("Error during reconciliation: {}", e.message, e)
            span.recordException(e)
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Error processing reconciliation job")
        } finally {
            span.end()
        }
    }

    log.info("Reconciliation Service started successfully")
}
