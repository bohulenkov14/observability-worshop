package com.example

import com.example.api.CurrencyExchangeApi
import com.example.usecase.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.DebuggingFilters.PrintRequest
import org.http4k.filter.OpenTelemetryMetrics
import org.http4k.filter.OpenTelemetryTracing
import org.http4k.filter.ServerFilters
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CurrencyExchangeService")

fun main() {
    // Initialize OpenTelemetry
    val openTelemetry: OpenTelemetry = initializeOpenTelemetry()
    val tracer: Tracer = openTelemetry.getTracer("currency-exchange-service")

    // Initialize use cases
    val convertCurrencyUseCase = ConvertCurrencyUseCase(tracer)

    // Initialize API layer
    val currencyExchangeApi = CurrencyExchangeApi(convertCurrencyUseCase)

    // Create the HTTP handler with all routes
    val app: HttpHandler = currencyExchangeApi.routes

    // Add middleware
    val printingApp: HttpHandler = PrintRequest()
        .then(ServerFilters.OpenTelemetryTracing())
        .then(ServerFilters.OpenTelemetryMetrics.RequestCounter())
        .then(ServerFilters.OpenTelemetryMetrics.RequestTimer())
        .then(app)

    // Start the server
    val server = printingApp.asServer(Jetty(8080)).start()
    log.info("Server started on port ${server.port()}")
}

private fun initializeOpenTelemetry(): OpenTelemetry {
    // In a real application, this would be configured with proper exporters and samplers
    return OpenTelemetry.noop()
}
