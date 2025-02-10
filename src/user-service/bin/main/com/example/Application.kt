package com.example

import com.example.config.AppConfig
import com.example.repository.UserRepository
import com.example.service.UserService
import com.sksamuel.hoplite.ConfigLoader
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.DebuggingFilters.PrintRequest
import org.http4k.filter.OpenTelemetryMetrics
import org.http4k.filter.OpenTelemetryTracing
import org.http4k.filter.ServerFilters
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.path
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.http4k.format.Jackson.auto
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

// Data classes for request payloads
data class CreateUserRequest(
    val username: String, 
    val email: String,
    val externalId: String
)
data class UpdateBalanceRequest(val userId: String, val newBalance: BigDecimal)
data class FreezeAccountRequest(val userId: String)
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null, val errorCode: String? = null)
data class CreateUserResponse(
    val id: String,
    val username: String,
    val email: String,
    val externalId: String
)
data class UserBalance(
    val userId: String, 
    val balance: BigDecimal,
    val lastUpdatedAt: Instant
)
data class UserInfoResponse(
    val id: String,
    val username: String,
    val email: String,
    val externalId: String,
    val balance: BigDecimal,
    val isFrozen: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

private val log = LoggerFactory.getLogger("UserService")

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    
    Database.connect(
        url = config.database.jdbcUrl,
        user = config.database.user,
        password = config.database.password,
        driver = "org.postgresql.Driver"
    )

    val userRepository = UserRepository()
    val userService = UserService(userRepository)

    // Lenses for JSON binding
    val createUserLens = Body.auto<CreateUserRequest>().toLens()
    val updateBalanceLens = Body.auto<UpdateBalanceRequest>().toLens()
    val freezeAccountLens = Body.auto<FreezeAccountRequest>().toLens()

    val app: HttpHandler = routes(
        "/health" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "User Service is healthy"
            ))
        },

        "/user/create" bind POST to { req ->
            val createUserReq = createUserLens(req)
            val span = Span.current()
            span.setAttribute("req.email", createUserReq.email)
            span.setAttribute("req.username", createUserReq.username)
            span.setAttribute("req.externalId", createUserReq.externalId)

            val user = userService.createUser(
                createUserReq.username, 
                createUserReq.email,
                createUserReq.externalId
            )
            
            val response = ApiResponse(
                status = "success",
                data = CreateUserResponse(
                    id = user.id,
                    username = user.username,
                    email = user.email,
                    externalId = user.externalId
                ),
                message = "User created successfully"
            )
            span.addEvent(
                "userCreated",
                Attributes.of(
                    AttributeKey.stringKey("id"), user.id,
                    AttributeKey.stringKey("username"), user.username,
                    AttributeKey.stringKey("email"), user.email,
                    AttributeKey.stringKey("externalId"), user.externalId,
                )
            )
            Response(CREATED).with(apiResponseLens<CreateUserResponse>() of response)
        },

        "/user/balance/update" bind POST to { req ->
            val updateReq = updateBalanceLens(req)
            val span = Span.current()
            span.setAttribute("req.userId", updateReq.userId)
            span.setAttribute("req.newBalance", updateReq.newBalance.toDouble())

            val user = userService.updateBalance(updateReq.userId, updateReq.newBalance)
            
            if (user == null) {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            } else {
                Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "success",
                    message = "Balance updated successfully"
                ))
            }
        },

        "/user/freeze" bind POST to { req ->
            val freezeReq = freezeAccountLens(req)
            val span = Span.current()
            span.setAttribute("req.userId", freezeReq.userId)

            val user = userService.freezeAccount(freezeReq.userId)
            
            if (user == null) {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            } else {
                Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "success",
                    message = "Account frozen successfully"
                ))
            }
        },

        "/user/unfreeze" bind POST to { req ->
            val freezeReq = freezeAccountLens(req)
            val span = Span.current()
            span.setAttribute("req.userId", freezeReq.userId)

            val user = userService.unfreezeAccount(freezeReq.userId)
            
            if (user == null) {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            } else {
                Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "success",
                    message = "Account unfrozen successfully"
                ))
            }
        },

        "/user/balance/{userId}" bind GET to { req ->
            val userId = req.path("userId") ?: return@to Response(BAD_REQUEST).with(
                apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "Missing user ID"
                )
            )
            val span = Span.current()
            span.setAttribute("req.userId", userId)
            
            val user = userService.findUser(userId)
            if (user == null) {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            } else {
                Response(OK).with(apiResponseLens<UserBalance>() of ApiResponse(
                    status = "success",
                    data = UserBalance(
                        userId = user.id,
                        balance = user.balance,
                        lastUpdatedAt = user.updatedAt
                    ),
                    message = "Balance retrieved successfully"
                ))
            }
        },

        "/user/{userId}" bind GET to { req ->
            val userId = req.path("userId") ?: return@to Response(BAD_REQUEST).with(
                apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "Missing user ID"
                )
            )
            
            val span = Span.current()
            span.setAttribute("req.userId", userId)

            val user = userService.findUser(userId)
            
            if (user == null) {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            } else {
                Response(OK).with(apiResponseLens<UserInfoResponse>() of ApiResponse(
                    status = "success",
                    data = UserInfoResponse(
                        id = user.id,
                        username = user.username,
                        email = user.email,
                        externalId = user.externalId,
                        balance = user.balance,
                        isFrozen = user.isFrozen,
                        createdAt = user.createdAt,
                        updatedAt = user.updatedAt
                    ),
                    message = "User found"
                ))
            }
        }
    )

    val printingApp: HttpHandler = PrintRequest()
        .then(ServerFilters.OpenTelemetryTracing())
        .then(ServerFilters.OpenTelemetryMetrics.RequestCounter())
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(app)

    val server = printingApp.asServer(Jetty(8080))
    server.start()
}
