package com.example

import com.example.formats.JacksonMessage
import com.example.formats.jacksonMessageLens
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.CREATED
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
import org.slf4j.LoggerFactory
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.kotlin.loggerOf

// Data classes for request payloads
data class CreateUserRequest(val username: String, val email: String)
data class TopUpRequest(val userId: String, val amount: Double)
data class CreateTransactionRequest(val userId: String, val transactionAmount: Double, val description: String)
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    val logger = loggerOf("PublicApi")

    // Lenses for JSON binding
    val createUserLens = Body.auto<CreateUserRequest>().toLens()
    val topUpLens = Body.auto<TopUpRequest>().toLens()
    val createTransactionLens = Body.auto<CreateTransactionRequest>().toLens()    

    val app: HttpHandler = routes(
        "/" bind GET to {
            val response = ApiResponse<Unit>(status = "success", message = "Welcome to the Public API Gateway")
            Response(OK).with(apiResponseLens<Unit>() of response)
        },
        
        "/user/create" bind POST to { req ->
            val createUserReq = createUserLens(req)
            logger.info("Creating user") {
                mapOf(
                    "username" to createUserReq.username,
                    "email" to createUserReq.email,
                    "event" to "user_create"
                )
            }
            val response = ApiResponse(
                status = "success",
                data = createUserReq,
                message = "User created successfully"
            )
            Response(CREATED).with(apiResponseLens<CreateUserRequest>() of response)
        },

        "/user/topup" bind POST to { req ->
            val topUpReq = topUpLens(req)
            logger.info("Processing top-up") {
                mapOf(
                    "user_id" to topUpReq.userId,
                    "amount" to topUpReq.amount,
                    "event" to "user_topup"
                )
            }
            val response = ApiResponse(
                status = "success",
                data = topUpReq,
                message = "Top-up processed successfully"
            )
            Response(OK).with(apiResponseLens<TopUpRequest>() of response)
        },

        "/transaction/create" bind POST to { req ->
            val txReq = createTransactionLens(req)
            logger.info("Creating transaction") {
                mapOf(
                    "user_id" to txReq.userId,
                    "amount" to txReq.transactionAmount,
                    "description" to txReq.description,
                    "event" to "transaction_create"
                )
            }
            val response = ApiResponse(
                status = "success",
                data = txReq,
                message = "Transaction created successfully"
            )
            Response(CREATED).with(apiResponseLens<CreateTransactionRequest>() of response)
        },

        "/ping" bind GET to {
            Response(OK).body("pong")
        },

        "/formats/json/jackson" bind GET to {
            Response(OK).with(jacksonMessageLens of JacksonMessage("Barry", "Hello there!"))
        },

        "/opentelemetrymetrics" bind GET to {
            Response(OK).body("Example metrics route for PublicApi")
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
