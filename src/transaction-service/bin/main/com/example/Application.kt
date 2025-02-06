package com.example

import com.example.config.AppConfig
import com.example.repository.TransactionRepository
import com.example.service.TransactionService
import com.sksamuel.hoplite.ConfigLoader
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
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import org.http4k.routing.path
import org.http4k.client.OkHttp
import org.http4k.filter.ClientFilters
import org.http4k.core.Uri

// Data classes for request payloads
data class CreateTransactionRequest(
    val userId: String,
    val amount: Double,
    val description: String,
    val currency: String? = "USD",
)

data class TransactionResponse(
    val id: String,
    val userId: String,
    val amount: Double,
    val description: String,
    val createdAt: String
)

data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

private val log = LoggerFactory.getLogger("TransactionService")

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    val config = ConfigLoader().loadConfigOrThrow<AppConfig>("/application.yaml")
    
    Database.connect(
        url = config.database.jdbcUrl,
        user = config.database.user,
        password = config.database.password,
        driver = "org.postgresql.Driver"
    )

    val transactionRepository = TransactionRepository()
    val currencyExchangeClient = OkHttp()
    val transactionService = TransactionService(transactionRepository, currencyExchangeClient)

    val createTransactionLens = Body.auto<CreateTransactionRequest>().toLens()

    val app: HttpHandler = routes(
        "/health" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "Transaction Service is healthy"
            ))
        },

        "/transaction/create" bind POST to { req ->
            val createReq = createTransactionLens(req)
            val transaction = transactionService.createTransaction(
                createReq.userId,
                BigDecimal.valueOf(createReq.amount),
                createReq.description,
                createReq.currency ?: "USD"
            )
            
            val response = ApiResponse(
                status = "success",
                data = TransactionResponse(
                    id = transaction.id,
                    userId = transaction.userId,
                    amount = transaction.amount.toDouble(),
                    description = transaction.description,
                    createdAt = transaction.createdAt.toString()
                ),
                message = "Transaction created successfully"
            )
            Response(CREATED).with(apiResponseLens<TransactionResponse>() of response)
        },

        "/transaction/user/{userId}" bind GET to { req ->
            val userId = req.path("userId") ?: return@to Response(Status.BAD_REQUEST).with(
                apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "Missing user ID"
                )
            )
            
            val transactions = transactionService.getUserTransactions(userId)
            
            val response = ApiResponse(
                status = "success",
                data = transactions.map { tx ->
                    TransactionResponse(
                        id = tx.id,
                        userId = tx.userId,
                        amount = tx.amount.toDouble(),
                        description = tx.description,
                        createdAt = tx.createdAt.toString()
                    )
                },
                message = "Transactions retrieved successfully"
            )
            Response(OK).with(apiResponseLens<List<TransactionResponse>>() of response)
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
