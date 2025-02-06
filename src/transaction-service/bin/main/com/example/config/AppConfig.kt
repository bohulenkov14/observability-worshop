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

data class AppConfig(
    val database: DatabaseConfig
) 