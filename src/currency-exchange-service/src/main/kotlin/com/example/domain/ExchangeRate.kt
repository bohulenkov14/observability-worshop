package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class ExchangeRate(
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val rate: BigDecimal
) {
    companion object {
        // In a real application, these rates would come from a repository
        private val USD_RATES = mapOf(
            Currency.EUR to BigDecimal("1.1"),    // 1 EUR = 1.1 USD
            Currency.GBP to BigDecimal("1.3"),    // 1 GBP = 1.3 USD
            Currency.USD to BigDecimal.ONE,       // Base currency
            Currency.JPY to BigDecimal("0.009"),  // 1 JPY = 0.009 USD
            Currency.CAD to BigDecimal("0.8"),    // 1 CAD = 0.8 USD
            Currency.AUD to BigDecimal("0.75"),   // 1 AUD = 0.75 USD
            Currency.CHF to BigDecimal("1.05"),   // 1 CHF = 1.05 USD
            Currency.CNY to BigDecimal("0.15")    // 1 CNY = 0.15 USD
        )

        fun between(fromCurrency: Currency, toCurrency: Currency): ExchangeRate {
            val rate = when {
                fromCurrency == toCurrency -> BigDecimal.ONE
                fromCurrency == Currency.USD -> USD_RATES[toCurrency]
                    ?: throw UnsupportedCurrencyException(toCurrency)
                toCurrency == Currency.USD -> BigDecimal.ONE.divide(USD_RATES[fromCurrency]
                    ?: throw UnsupportedCurrencyException(fromCurrency), 6, RoundingMode.HALF_UP)
                else -> {
                    val fromRate = USD_RATES[fromCurrency] ?: throw UnsupportedCurrencyException(fromCurrency)
                    val toRate = USD_RATES[toCurrency] ?: throw UnsupportedCurrencyException(toCurrency)
                    toRate.divide(fromRate, 6, RoundingMode.HALF_UP)
                }
            }
            return ExchangeRate(fromCurrency, toCurrency, rate)
        }
    }
}

enum class Currency {
    USD, EUR, GBP, JPY, CAD, AUD, CHF, CNY;

    companion object {
        fun fromString(value: String): Currency = valueOf(value.uppercase())
    }
}

data class CurrencyConversion(
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val amount: BigDecimal,
    val convertedAmount: BigDecimal,
    val appliedRate: BigDecimal
) {
    companion object {
        fun convert(amount: BigDecimal, fromCurrency: Currency, toCurrency: Currency): CurrencyConversion {
            val exchangeRate = ExchangeRate.between(fromCurrency, toCurrency)
            val convertedAmount = amount
                .multiply(exchangeRate.rate)
                .setScale(2, RoundingMode.HALF_UP)

            return CurrencyConversion(
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                amount = amount,
                convertedAmount = convertedAmount,
                appliedRate = exchangeRate.rate
            )
        }
    }
}

class UnsupportedCurrencyException(currency: Currency) : 
    IllegalArgumentException("Unsupported currency: $currency") 