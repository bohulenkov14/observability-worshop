package com.example.domain

import java.math.BigDecimal
import java.time.Instant

data class User(
    val id: String,
    val username: String,
    val email: String,
    val balance: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant
) 