package com.example.api

import com.example.domain.*
import com.example.usecase.*
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.http4k.core.*
import org.http4k.core.Method.GET
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.format.Jackson.auto
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class CurrencyExchangeApi(
    private val convertCurrencyUseCase: ConvertCurrencyUseCase
) {
    private val log = LoggerFactory.getLogger(CurrencyExchangeApi::class.java)

    data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

    data class CurrencyConversionResponse(
        val fromCurrency: String,
        val toCurrency: String,
        val amount: BigDecimal,
        val convertedAmount: BigDecimal,
        val rate: BigDecimal
    )

    private inline fun <reified T> apiResponseLens() = Body.auto<ApiResponse<T>>().toLens()

    val routes: HttpHandler = routes(
        "/health" bind GET to {
            Response(OK).with(apiResponseLens<Unit>() of ApiResponse(
                status = "success",
                message = "Currency Exchange Service is healthy"
            ))
        },

        "/convert" bind GET to { req ->
            log.info("Received /convert request with headers: {}", req.headers)

            val amount = req.query("amount")?.toBigDecimalOrNull()
            val fromCurrency = req.query("from")
            val toCurrency = req.query("to")

            val span = Span.current()
            span.setAttribute("req.amount", amount.toString())
            span.setAttribute("req.fromCurrency", fromCurrency ?: "null")
            span.setAttribute("req.toCurrency", toCurrency ?: "null")

            try {
                if (amount == null || fromCurrency == null || toCurrency == null) {
                    span.setStatus(StatusCode.ERROR)
                    span.addEvent("requestRequiredParametersMissed")
                    Response(BAD_REQUEST).with(apiResponseLens<Unit>() of ApiResponse(
                        status = "error",
                        message = "Missing required parameters"
                    ))
                } else {
                    val conversion = convertCurrencyUseCase.execute(
                        ConvertCurrencyInput(
                            amount = amount,
                            fromCurrency = Currency.fromString(fromCurrency),
                            toCurrency = Currency.fromString(toCurrency)
                        )
                    )

                    Response(OK).with(apiResponseLens<CurrencyConversionResponse>() of ApiResponse(
                        status = "success",
                        data = CurrencyConversionResponse(
                            fromCurrency = conversion.fromCurrency.name,
                            toCurrency = conversion.toCurrency.name,
                            amount = conversion.amount,
                            convertedAmount = conversion.convertedAmount,
                            rate = conversion.appliedRate
                        )
                    ))
                }
            } catch (e: IllegalArgumentException) {
                log.error("Error processing conversion request", e)
                span.setStatus(StatusCode.ERROR)
                span.recordException(e)
                Response(BAD_REQUEST).with(apiResponseLens<Unit>() of ApiResponse(
                    status = "error",
                    message = e.message
                ))
            }
        }
    )
} 