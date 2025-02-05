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

// Data classes for request payloads
data class CreateUserRequest(val username: String, val email: String)
data class TopUpRequest(val userId: String, val amount: Double)
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

private val log = LoggerFactory.getLogger("UserService")

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    // Lenses for JSON binding
    val createUserLens = Body.auto<CreateUserRequest>().toLens()
    val topUpLens = Body.auto<TopUpRequest>().toLens()

    val app: HttpHandler = routes(
        "/" bind GET to {
            log.info("Received request to welcome endpoint")
            val response = ApiResponse<Unit>(status = "success", message = "Welcome to the User Service")
            Response(OK).with(apiResponseLens<Unit>() of response)
        },
        
        "/user/create" bind POST to { req ->
            val createUserReq = createUserLens(req)
            log.info("Creating user - username: {}, email: {}, event: {}", 
                createUserReq.username, 
                createUserReq.email, 
                "user_create"
            )
            val response = ApiResponse(
                status = "success",
                data = createUserReq,
                message = "User created successfully"
            )
            Response(CREATED).with(apiResponseLens<CreateUserRequest>() of response)
        },

        "/user/topup" bind POST to { req ->
            val topUpReq = topUpLens(req)
            log.info("Processing top-up - user_id: {}, amount: {}, event: {}", 
                topUpReq.userId, 
                topUpReq.amount, 
                "user_topup"
            )
            val response = ApiResponse(
                status = "success",
                data = topUpReq,
                message = "Top-up processed successfully"
            )
            Response(OK).with(apiResponseLens<TopUpRequest>() of response)
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
