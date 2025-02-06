package com.example

import com.example.formats.JacksonMessage
import com.example.formats.jacksonMessageLens
import org.http4k.client.OkHttp
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.CREATED
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
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
import org.slf4j.LoggerFactory

// Data classes for request payloads
data class CreateUserRequest(val username: String, val email: String)
data class TopUpRequest(val userId: String, val amount: Double)
data class CreateTransactionRequest(val userId: String, val transactionAmount: Double, val description: String)
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

private val log = LoggerFactory.getLogger("PublicApi")
private val client = OkHttp()
private const val USER_SERVICE_URL = "http://user-service:8080"
private const val TRANSACTION_SERVICE_URL = "http://transaction-service:8080"

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun proxyRequest(request: Request, baseUrl: String, path: String): Response {
    val proxyRequest = request.uri(Uri.of("$baseUrl$path"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
    
    log.info("Proxying request to: ${proxyRequest.uri}")
    return try {
        client(proxyRequest)
    } catch (e: Exception) {
        log.error("Error proxying request: ${e.message}", e)
        Response(Status.INTERNAL_SERVER_ERROR)
            .with(apiResponseLens<Unit>() of ApiResponse(
                status = "error",
                message = "Internal server error"
            ))
    }
}

fun main() {
    // Lenses for JSON binding
    val createUserLens = Body.auto<CreateUserRequest>().toLens()
    val topUpLens = Body.auto<TopUpRequest>().toLens()
    val createTransactionLens = Body.auto<CreateTransactionRequest>().toLens()    

    val app: HttpHandler = routes(
        "/" bind GET to {
            log.info("Received request to welcome endpoint")
            val response = ApiResponse<Unit>(status = "success", message = "Welcome to the Public API Gateway")
            Response(OK).with(apiResponseLens<Unit>() of response)
        },
        
        "/user/create" bind POST to { req ->
            log.info("Routing create user request to user-service")
            proxyRequest(req, USER_SERVICE_URL, "/user/create")
        },

        "/user/topup" bind POST to { req ->
            log.info("Routing top-up request to user-service")
            proxyRequest(req, USER_SERVICE_URL, "/user/topup")
        },

        "/transaction/create" bind POST to { req ->
            log.info("Routing create transaction request to transaction-service")
            proxyRequest(req, TRANSACTION_SERVICE_URL, "/transaction/create")
        },

        "/transaction/user/{userId}" bind GET to { req ->
            val userId = req.path("userId") ?: return@to Response(Status.BAD_REQUEST).with(
                apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "Missing user ID"
                )
            )
            log.info("Routing get user transactions request to transaction-service for user: {}", userId)
            proxyRequest(req, TRANSACTION_SERVICE_URL, "/transaction/user/$userId")
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
