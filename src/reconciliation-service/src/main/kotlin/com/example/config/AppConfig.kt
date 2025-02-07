package com.example.config

data class KafkaConfig(
    val bootstrapServers: String = "localhost:9092",
    val groupId: String = "reconciliation-service",
    val topic: String = "transactions"
)

data class AppConfig(
    val kafka: KafkaConfig = KafkaConfig()
) 