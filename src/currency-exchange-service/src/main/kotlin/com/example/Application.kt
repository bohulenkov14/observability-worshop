package com.example

import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.BAD_REQUEST
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
import java.math.BigDecimal

data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

data class CurrencyConversionResponse(
    val fromCurrency: String,
    val toCurrency: String,
    val amount: BigDecimal,
    val convertedAmount: BigDecimal,
    val rate: BigDecimal
)

private val log = LoggerFactory.getLogger("CurrencyExchangeService")

inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

fun main() {
    val app: HttpHandler = routes(
        "/health" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "Currency Exchange Service is healthy"
            ))
        },

        "/convert" bind GET to { req ->
            // Log all incoming request headers for debugging purposes
            log.info("Received /convert request with headers: {}", req.headers)
            
            val amount = req.query("amount")?.toBigDecimalOrNull()
            val fromCurrency = req.query("from")
            val toCurrency = req.query("to")

            if (amount == null || fromCurrency == null || toCurrency == null) {
                Response(BAD_REQUEST).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = "Missing required parameters"
                ))
            } else {
                val rate = when (fromCurrency.uppercase()) {
                    "EUR" -> BigDecimal("1.1")    // 1 EUR = 1.1 USD
                    "GBP" -> BigDecimal("1.3")    // 1 GBP = 1.3 USD
                    "USD" -> BigDecimal.ONE
                    "JPY" -> BigDecimal("0.009")  // 1 JPY = 0.009 USD
                    "CAD" -> BigDecimal("0.8")    // 1 CAD = 0.8 USD
                    "AUD" -> BigDecimal("0.75")   // 1 AUD = 0.75 USD
                    "CHF" -> BigDecimal("1.05")   // 1 CHF = 1.05 USD
                    "CNY" -> BigDecimal("0.15")   // 1 CNY = 0.15 USD
                    else -> throw IllegalArgumentException("Unsupported currency: $fromCurrency")
                }

                val convertedAmount = amount * rate
                Response(OK).with(apiResponseLens<CurrencyConversionResponse>() of ApiResponse(
                    status = "success",
                    data = CurrencyConversionResponse(
                        fromCurrency = fromCurrency.uppercase(),
                        toCurrency = toCurrency.uppercase(),
                        amount = amount,
                        convertedAmount = convertedAmount,
                        rate = rate
                    )
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
