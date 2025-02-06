package com.example

import com.example.config.AppConfig
import com.example.repository.UserRepository
import com.example.service.UserService
import com.sksamuel.hoplite.ConfigLoader
import com.example.formats.JacksonMessage
import com.example.formats.jacksonMessageLens
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.DebuggingFilters.PrintRequest
import org.http4k.filter.OpenTelemetryMetrics
import org.http4k.filter.OpenTelemetryTracing
import org.http4k.filter.ServerFilters
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.http4k.format.Jackson.auto
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.math.BigDecimal

// Data classes for request payloads
data class CreateUserRequest(val username: String, val email: String)
data class TopUpRequest(val userId: String, val amount: Double)
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)
data class CreateUserResponse(
    val id: String,
    val username: String,
    val email: String
)

private val log = LoggerFactory.getLogger("UserService")

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    
    // Initialize database
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
    val topUpLens = Body.auto<TopUpRequest>().toLens()

    val app: HttpHandler = routes(
        "/health" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "User Service is healthy"
            ))
        },
        "/" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "Welcome to the User Service"
            ))
        },
        
        "/user/create" bind POST to { req ->
            val createUserReq = createUserLens(req)
            val user = userService.createUser(createUserReq.username, createUserReq.email)
            
            val response = ApiResponse(
                status = "success",
                data = CreateUserResponse(
                    id = user.id,
                    username = user.username,
                    email = user.email
                ),
                message = "User created successfully"
            )
            Response(CREATED).with(apiResponseLens<CreateUserResponse>() of response)
        },

        "/user/topup" bind POST to { req ->
            val topUpReq = topUpLens(req)
            val updatedUser = userService.topUpBalance(
                topUpReq.userId,
                BigDecimal.valueOf(topUpReq.amount)
            )

            if (updatedUser != null) {
                Response(OK).with(apiResponseLens<TopUpRequest>() of ApiResponse(
                    status = "success",
                    data = topUpReq,
                    message = "Top-up processed successfully"
                ))
            } else {
                Response(NOT_FOUND).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "User not found"
                ))
            }
        }
    )

    val printingApp: HttpHandler = PrintRequest()
        .then(ServerFilters.OpenTelemetryTracing())
        .then(ServerFilters.OpenTelemetryMetrics.RequestCounter())
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(app)

    val server = printingApp.asServer(Jetty(8080)).start()

    println("Server started on " + server.port())
}
