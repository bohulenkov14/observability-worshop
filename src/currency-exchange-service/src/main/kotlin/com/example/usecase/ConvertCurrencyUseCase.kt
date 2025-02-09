package com.example.usecase

import com.example.domain.*
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.StatusCode
import org.slf4j.LoggerFactory
import java.math.BigDecimal

data class ConvertCurrencyInput(
    val amount: BigDecimal,
    val fromCurrency: Currency,
    val toCurrency: Currency
)

class ConvertCurrencyUseCase(private val tracer: Tracer) : UseCase<ConvertCurrencyInput, CurrencyConversion> {
    private val log = LoggerFactory.getLogger(ConvertCurrencyUseCase::class.java)

    override fun execute(input: ConvertCurrencyInput): CurrencyConversion {
        val span = tracer.spanBuilder("ConvertCurrency")
            .setAttribute("fromCurrency", input.fromCurrency.name)
            .setAttribute("toCurrency", input.toCurrency.name)
            .setAttribute("amount", input.amount.toString())
            .startSpan()

        try {
            log.info("Converting ${input.amount} from ${input.fromCurrency} to ${input.toCurrency}")
            return CurrencyConversion.convert(input.amount, input.fromCurrency, input.toCurrency)
        } catch (e: UnsupportedCurrencyException) {
            log.error("Failed to convert currency", e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            span.recordException(e)
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error during currency conversion", e)
            span.setStatus(StatusCode.ERROR, "Internal error during currency conversion")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
} 