package com.example.adapter

import com.example.application.port.UserBalancePort
import org.http4k.core.*
import org.http4k.format.Jackson.auto
import org.http4k.client.OkHttp
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class UserBalanceAdapter : UserBalancePort {
    private val log = LoggerFactory.getLogger(UserBalanceAdapter::class.java)
    private val client = OkHttp()
    private val userBalanceLens = Body.auto<ApiResponse<UserBalance>>().toLens()
    private val freezeAccountLens = Body.auto<FreezeAccountRequest>().toLens()

    override fun getUserBalance(userId: String): BigDecimal? {
        return try {
            val response = client(
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

    override fun freezeUserAccount(userId: String): Boolean {
        return try {
            val freezeRequest = FreezeAccountRequest(userId)
            val response = client(
                Request(Method.POST, "http://user-service:8080/user/freeze")
                    .with(freezeAccountLens of freezeRequest)
            )

            when (response.status) {
                Status.OK -> {
                    log.info("Successfully froze account for user {} due to balance discrepancy", userId)
                    true
                }
                Status.NOT_FOUND -> {
                    log.error("Could not freeze account - user {} not found", userId)
                    false
                }
                else -> {
                    log.error("Failed to freeze account for user {}. Status: {}", userId, response.status)
                    false
                }
            }
        } catch (e: Exception) {
            log.error("Error freezing user account {}: {}", userId, e.message, e)
            false
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