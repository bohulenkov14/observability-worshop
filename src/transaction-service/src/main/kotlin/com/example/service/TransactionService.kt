package com.example.service

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.domain.TransactionType
import com.example.repository.TransactionRepository
import org.http4k.core.*
import org.http4k.client.OkHttp
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.serialization.StringDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonAppend.Attr
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.semconv.SemanticAttributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.Properties
import org.http4k.format.Jackson.auto
import java.time.Duration
import kotlin.concurrent.thread

class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val currencyExchangeClient: HttpHandler
) {
    private val log = LoggerFactory.getLogger(TransactionService::class.java)
    private val producer: KafkaProducer<String, String>
    private val consumer: KafkaConsumer<String, String>
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
    }
    private val tracer = GlobalOpenTelemetry.getTracer("transaction-service")
    private val propagator = GlobalOpenTelemetry.getPropagators().textMapPropagator
    private val meter = GlobalOpenTelemetry.getMeter("transaction-service")
    
    // Business metrics
    private val transactionProcessingTime: DoubleHistogram = meter.histogramBuilder("transaction_processing_duration_ms")
        .setDescription("Time taken to process a transaction from creation to completion")
        .setUnit("milliseconds")
        .build()

    // Transaction Volume Metrics
    private val transactionVolume = meter.counterBuilder("transaction_volume_total")
        .setDescription("Total number of transactions")
        .setUnit("1")
        .build()

    // Currency Exchange Metrics
    private val currencyConversionVolume = meter.counterBuilder("currency_conversion_total")
        .setDescription("Number of currency conversions performed")
        .setUnit("1")
        .build()

    // User Activity Metrics
    private val userTransactionFrequency = meter.histogramBuilder("user_transaction_frequency")
        .setDescription("Time between user transactions")
        .setUnit("seconds")
        .build()

    // Balance-related Metrics
    private val balanceUpdateLatency = meter.histogramBuilder("balance_update_duration_ms")
        .setDescription("Time taken to update user balance")
        .setUnit("milliseconds")
        .build()

    // Transaction Amount Metrics
    private val transactionAmountUSD = meter.histogramBuilder("transaction_amount_usd")
        .setDescription("Distribution of transaction amounts in USD")
        .setUnit("USD")
        .setExplicitBucketBoundariesAdvice(listOf(
            100.0, 250.0, 500.0, 750.0, 1000.0, 1250.0, 1500.0, 1750.0, 2000.0
        ))
        .build()

    private val transactionAmountEUR = meter.histogramBuilder("transaction_amount_eur")
        .setDescription("Distribution of transaction amounts in EUR")
        .setUnit("EUR")
        .setExplicitBucketBoundariesAdvice(listOf(
            100.0, 250.0, 500.0, 750.0, 1000.0, 1250.0, 1500.0, 1750.0, 2000.0
        ))
        .build()

    private val transactionAmountGBP = meter.histogramBuilder("transaction_amount_gbp")
        .setDescription("Distribution of transaction amounts in GBP")
        .setUnit("GBP")
        .setExplicitBucketBoundariesAdvice(listOf(
            100.0, 250.0, 500.0, 750.0, 1000.0, 1250.0, 1500.0, 1750.0, 2000.0
        ))
        .build()

    private val transactionAmountJPY = meter.histogramBuilder("transaction_amount_jpy")
        .setDescription("Distribution of transaction amounts in JPY")
        .setUnit("JPY")
        .setExplicitBucketBoundariesAdvice(listOf(
            50000.0, 75000.0, 100000.0, 125000.0, 150000.0, 175000.0, 200000.0, 225000.0, 250000.0
        ))
        .build()

    private val conversionResponseLens = Body.auto<ApiResponse<CurrencyConversionResponse>>().toLens()
    private val apiResponseLens = Body.auto<ApiResponse<Unit>>().toLens()
    private val userServiceClient = OkHttp()

    companion object {
        const val FRAUD_CHECK_TOPIC = "transactions.fraud.check"
        const val FRAUD_RESULT_TOPIC = "transactions.fraud.result"
    }

    init {
        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092")
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }
        producer = KafkaProducer(producerProps)

        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092")
            put(ConsumerConfig.GROUP_ID_CONFIG, "transaction-service")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        consumer = KafkaConsumer<String, String>(consumerProps).apply {
            subscribe(listOf(FRAUD_RESULT_TOPIC))
        }

        startFraudResultConsumer()
    }

    private fun startFraudResultConsumer() {
        thread(start = true, name = "fraud-result-consumer") {
            try {
                while (true) {
                    val records = consumer.poll(Duration.ofMillis(100))
                    for (record in records) {
                        processFraudCheckResult(record)
                    }
                }
            } catch (e: Exception) {
                log.error("Error in fraud result consumer: {}", e.message, e)
            }
        }
    }

    private fun processFraudCheckResult(record: ConsumerRecord<String, String>) {
        val span = tracer.spanBuilder("processFraudCheckResult")
            .startSpan()
        try {
            span.makeCurrent().use { ctx ->
                val transactionId = record.key()
                span.setAttribute(AttributeKey.stringKey("record.transactionId"), transactionId)
                log.info("Received fraud check result for transaction: {}", transactionId)

                val updatedTransaction = transactionRepository.updateStatus(
                    transactionId,
                    TransactionStatus.PENDING_BALANCE_DEDUCTION
                )


                if (updatedTransaction != null) {
                    // Update user's balance based on transaction type
                    when (updatedTransaction.type) {
                        TransactionType.TOP_UP -> {
                            span.addEvent(
                                "topUpTransactionApproved",
                                updatedTransaction.toEventAttributes()
                            )

                            val response = userServiceClient(
                                Request(Method.POST, "http://user-service:8080/user/balance/update")
                                    .with(
                                        Body.auto<UpdateBalanceRequest>().toLens() of UpdateBalanceRequest(
                                            userId = updatedTransaction.userId,
                                            newBalance = getCurrentBalance(updatedTransaction.userId)?.plus(
                                                updatedTransaction.amount
                                            ) ?: updatedTransaction.amount
                                        )
                                    )
                            )

                            handleBalanceUpdateResponse(response, transactionId)
                        }

                        TransactionType.PURCHASE -> {
                            val response = userServiceClient(
                                Request(Method.POST, "http://user-service:8080/user/balance/update")
                                    .with(
                                        Body.auto<UpdateBalanceRequest>().toLens() of UpdateBalanceRequest(
                                            userId = updatedTransaction.userId,
                                            newBalance = getCurrentBalance(updatedTransaction.userId)?.minus(
                                                updatedTransaction.amount
                                            ) ?: -updatedTransaction.amount
                                        )
                                    )
                            )

                            handleBalanceUpdateResponse(response, transactionId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error processing fraud result: {}", e.message, e)
            span.recordException(e)
        } finally {
            span.end()
        }
    }

    private fun getCurrentBalance(userId: String): BigDecimal? {
        val response = userServiceClient(
            Request(Method.GET, "http://user-service:8080/user/balance/$userId")
        )

        if (response.status != Status.OK) {
            log.error("Failed to get user balance. Status: {}", response.status)
            return null
        }

        return Body.auto<ApiResponse<UserBalance>>().toLens()(response).data?.balance
    }

    private fun handleBalanceUpdateResponse(response: Response, transactionId: String) {
        val startTime = System.currentTimeMillis()
        when (response.status) {
            Status.OK -> {
                transactionRepository.updateStatus(transactionId, TransactionStatus.PROCESSED)
                log.info("Successfully processed transaction: {}", transactionId)
                
                // Record metrics
                val transaction = transactionRepository.findById(transactionId)
                if (transaction != null) {
                    val processingDurationMs = System.currentTimeMillis() - transaction.createdAt.toEpochMilli()
                    
                    // Record processing time
                    transactionProcessingTime.record(
                        processingDurationMs.toDouble(),
                        Attributes.of(
                            AttributeKey.stringKey("transaction_type"), transaction.type.name,
                            AttributeKey.stringKey("status"), "success"
                        )
                    )

                    // Record transaction volume
                    transactionVolume.add(1, Attributes.of(
                        AttributeKey.stringKey("transaction_type"), transaction.type.name,
                        AttributeKey.stringKey("status"), "success"
                    ))

                    // Record balance update latency
                    balanceUpdateLatency.record((System.currentTimeMillis() - startTime).toDouble())

                    Span.current().addEvent(
                        "transactionAppliedToUserBalance",
                        transaction.toEventAttributes()
                    )
                }
            }
            Status.BAD_REQUEST -> {
                val apiResponse = apiResponseLens(response)
                recordFailedTransaction(transactionId, "bad_request")
            }
            else -> {
                log.error("Failed to update balance for transaction {}: {}", transactionId, response.status)
                recordFailedTransaction(transactionId, "error")
            }
        }
    }

    private fun recordFailedTransaction(transactionId: String, reason: String) {
        val transaction = transactionRepository.findById(transactionId)
        if (transaction != null) {
            val processingDurationMs = System.currentTimeMillis() - transaction.createdAt.toEpochMilli()
            transactionProcessingTime.record(
                processingDurationMs.toDouble(),
                Attributes.of(
                    AttributeKey.stringKey("transaction_type"), transaction.type.name,
                    AttributeKey.stringKey("status"), reason
                )
            )

            Span.current()
                .setStatus(StatusCode.ERROR)
                .setAttribute("failure.reason", reason)
                .addEvent(
                    "transactionFailedToApplyToUserBalance",
                    transaction.toEventAttributes()
                )
        }
    }

    private val headersSetter = TextMapSetter<MutableList<RecordHeader>> { headers, key, value ->
        requireNotNull(headers) { "Headers list cannot be null" }
        headers.add(RecordHeader(key, value.toByteArray()))
    }

    fun createTopUp(userId: String, amount: BigDecimal): Transaction {
        log.info("Creating top-up - user_id: {}, amount: {}", userId, amount)
        
        // Record transaction amount
        transactionAmountUSD.record(amount.toDouble(), Attributes.of(
            AttributeKey.stringKey("transaction_type"), TransactionType.TOP_UP.name,
            AttributeKey.stringKey("currency"), "USD"
        ))
        
        val transaction = transactionRepository.create(
            userId = userId,
            amount = amount,
            description = "TOP-UP: Account balance top-up",
            type = TransactionType.TOP_UP
        )
        val span = Span.current()
        span.addEvent(
            "topUpTransactionCreated",
            transaction.toEventAttributes()
        )
        
        // Record user transaction frequency for top-ups
        recordTransactionFrequency(userId, TransactionType.TOP_UP)
        
        publishToFraudCheck(transaction)
        return transaction
    }

    fun createPurchase(userId: String, amount: BigDecimal, description: String, currency: String = "USD"): Transaction {
        log.info("Creating purchase - user_id: {}, amount: {}, currency: {}", userId, amount, currency)
        
        // Record transaction amount in currency-specific metric
        when (currency) {
            "USD" -> transactionAmountUSD.record(amount.toDouble(), Attributes.of(
                AttributeKey.stringKey("transaction_type"), TransactionType.PURCHASE.name,
                AttributeKey.stringKey("currency"), "USD"
            ))
            "EUR" -> transactionAmountEUR.record(amount.toDouble(), Attributes.of(
                AttributeKey.stringKey("transaction_type"), TransactionType.PURCHASE.name,
                AttributeKey.stringKey("currency"), "EUR"
            ))
            "GBP" -> transactionAmountGBP.record(amount.toDouble(), Attributes.of(
                AttributeKey.stringKey("transaction_type"), TransactionType.PURCHASE.name,
                AttributeKey.stringKey("currency"), "GBP"
            ))
            "JPY" -> transactionAmountJPY.record(amount.toDouble(), Attributes.of(
                AttributeKey.stringKey("transaction_type"), TransactionType.PURCHASE.name,
                AttributeKey.stringKey("currency"), "JPY"
            ))
        }
        
        // Convert currency if not USD
        val usdAmount = if (currency != "USD") {
            val response = currencyExchangeClient(
                Request(Method.GET, "http://currency-exchange:8080/convert")
                    .query("amount", amount.toString())
                    .query("from", currency)
                    .query("to", "USD")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
            )
            
            if (response.status != Status.OK) {
                throw RuntimeException("Failed to convert currency: ${response.status}")
            }
            
            currencyConversionVolume.add(1, Attributes.of(
                AttributeKey.stringKey("from_currency"), currency,
                AttributeKey.stringKey("to_currency"), "USD"
            ))
            
            val conversionResponse = conversionResponseLens(response)
            conversionResponse.data?.convertedAmount 
                ?: throw RuntimeException("Currency conversion response was empty")
        } else {
            amount
        }

        val transaction = transactionRepository.create(
            userId = userId,
            amount = usdAmount,
            description = description,
            type = TransactionType.PURCHASE
        )
        val span = Span.current()
        span.addEvent(
            "purchaseTransactionCreated",
            transaction.toEventAttributes().toBuilder().put(
                "transaction.amountCurrency", amount.toString()
            ).build(),
        )
        
        // Record user transaction frequency for purchases
        recordTransactionFrequency(userId, TransactionType.PURCHASE)
        
        publishToFraudCheck(transaction)
        return transaction
    }

    private fun recordTransactionFrequency(userId: String, type: TransactionType) {
        val lastTransaction = transactionRepository.findByUserId(userId)
            .filter { it.type == type }
            .maxByOrNull { it.createdAt }
        
        if (lastTransaction != null) {
            val timeSinceLastTransaction = Duration.between(lastTransaction.createdAt, java.time.Instant.now()).seconds
            userTransactionFrequency.record(timeSinceLastTransaction.toDouble(), Attributes.of(
                AttributeKey.stringKey("transaction_type"), type.name
            ))
        }
    }

    private fun publishToFraudCheck(transaction: Transaction) {
        val span = tracer.spanBuilder("publish fraud check request")
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
            .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, FRAUD_CHECK_TOPIC)
            .setAttribute(SemanticAttributes.MESSAGING_OPERATION, "publish")
            .setAttribute("messaging.kafka.client_id", "transaction-service")
            .setAttribute("transaction.id", transaction.id)
            .setAttribute("transaction.userId", transaction.userId)
            .setAttribute("transaction.description", transaction.description)
            .setAttribute("transaction.amount", transaction.amount.toString())
            .setAttribute("transaction.createdAt", transaction.createdAt.toString())
            .setAttribute("transaction.status", transaction.status.name)
            .startSpan()

        try {
            span.makeCurrent().use { ctx ->
                val transactionJson = objectMapper.writeValueAsString(transaction)
                val headers = mutableListOf<RecordHeader>()
                
                propagator.inject(Context.current(), headers, headersSetter)
                
                val record = ProducerRecord(FRAUD_CHECK_TOPIC, null, transaction.id, transactionJson, headers)

                producer.send(record) { metadata, exception ->
                    if (exception != null) {
                        log.error("Failed to publish fraud check request: {}", exception.message)
                        span.recordException(exception)
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Failed to publish message")
                    } else {
                        log.info("Published fraud check request - topic: {}, partition: {}, offset: {}", 
                            metadata.topic(), metadata.partition(), metadata.offset())
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION, metadata.partition())
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET, metadata.offset())
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error publishing fraud check request: {}", e.message)
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, "Error publishing fraud check request")
        } finally {
            span.end()
        }
    }

    fun getUserTransactions(userId: String): List<Transaction> {
        log.info("Fetching transactions for user_id: {}", userId)
        return transactionRepository.findByUserId(userId)
    }

    private fun Transaction.toEventAttributes() = Attributes.of(
        AttributeKey.stringKey("transaction.id"), this.id,
        AttributeKey.stringKey("transaction.userId"), this.userId,
        AttributeKey.stringKey("transaction.description"), this.description,
        AttributeKey.stringKey("transaction.amount"), this.amount.toString(),
        AttributeKey.stringKey("transaction.createdAt"), this.createdAt.toString(),
        AttributeKey.stringKey("transaction.status"), this.status.name
    )
}

data class ApiResponse<T>(
    val status: String, 
    val data: T? = null, 
    val message: String? = null,
    val errorCode: String? = null
)

data class CurrencyConversionResponse(
    val fromCurrency: String,
    val toCurrency: String,
    val amount: BigDecimal,
    val convertedAmount: BigDecimal,
    val rate: BigDecimal
)

data class UpdateBalanceRequest(
    val userId: String,
    val newBalance: BigDecimal
)

data class UserBalance(
    val userId: String,
    val balance: BigDecimal
)