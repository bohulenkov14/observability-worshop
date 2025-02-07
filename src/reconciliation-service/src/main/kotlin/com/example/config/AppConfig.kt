package com.example.config

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String
) {
    val jdbcUrl: String
        get() = "jdbc:postgresql://$host:$port/$name"
}

data class KafkaConfig(
    val bootstrapServers: String = "localhost:9092",
    val groupId: String = "reconciliation-service",
    val topic: String = "transactions"
)

data class AppConfig(
    val database: DatabaseConfig,
    val kafka: KafkaConfig = KafkaConfig()
) 