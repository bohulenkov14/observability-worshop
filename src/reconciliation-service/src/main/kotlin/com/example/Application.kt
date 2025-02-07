package com.example

import com.example.config.AppConfig
import com.example.repository.ReconciliationRepository
import com.example.service.KafkaConsumerService
import com.example.service.ReconciliationService
import com.sksamuel.hoplite.ConfigLoader
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ReconciliationService")

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    
    // Connect to database
    Database.connect(
        url = config.database.jdbcUrl,
        user = config.database.user,
        password = config.database.password,
        driver = "org.postgresql.Driver"
    )

    // Initialize repositories and services
    val reconciliationRepository = ReconciliationRepository()
    
    // Initialize services
    val kafkaConsumerService = KafkaConsumerService(reconciliationRepository)
    val reconciliationService = ReconciliationService(
        reconciliationRepository = reconciliationRepository
    )

    log.info("Reconciliation Service started successfully")
}
