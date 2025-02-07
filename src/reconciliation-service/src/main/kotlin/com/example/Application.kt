package com.example

import com.example.config.AppConfig
import com.example.service.KafkaConsumerService
import com.sksamuel.hoplite.ConfigLoader
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ReconciliationService")

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    
    // Start Kafka consumer
    val kafkaBootstrapServers = System.getenv("KAFKA_ADDR") ?: "localhost:9092"
    val kafkaConsumer = KafkaConsumerService(kafkaBootstrapServers)
    
    log.info("Starting Reconciliation Service...")
    kafkaConsumer.startConsuming()
}
