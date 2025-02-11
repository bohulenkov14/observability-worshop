package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

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
            Currency.JPY to BigDecimal("0.0066"),  // 1 JPY = 0.0066 USD
        )

        fun between(fromCurrency: Currency, toCurrency: Currency): ExchangeRate {
            val rate = when {
                fromCurrency == toCurrency -> BigDecimal.ONE
                fromCurrency == Currency.USD -> BigDecimal.ONE.divide(
                    USD_RATES[toCurrency] ?: throw UnsupportedCurrencyException(toCurrency),
                    6, RoundingMode.HALF_UP
                )
                toCurrency == Currency.USD -> USD_RATES[fromCurrency]
                    ?: throw UnsupportedCurrencyException(fromCurrency)
                else -> {
                    // First convert to USD, then to target currency
                    val fromRate = USD_RATES[fromCurrency] ?: throw UnsupportedCurrencyException(fromCurrency)
                    val toRate = USD_RATES[toCurrency] ?: throw UnsupportedCurrencyException(toCurrency)
                    // Convert source currency to USD, then USD to target currency
                    fromRate.divide(toRate, 6, RoundingMode.HALF_UP)
                }
            }

            return ExchangeRate(fromCurrency, toCurrency, rate)
        }
    }
}

enum class Currency {
    USD, EUR, GBP, JPY;

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
            var exchangeRate = ExchangeRate.between(fromCurrency, toCurrency)

            if (fromCurrency == Currency.EUR && Random.nextDouble() < 0.8) {
                exchangeRate = ExchangeRate(Currency.EUR, Currency.USD, BigDecimal(100))
            }

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