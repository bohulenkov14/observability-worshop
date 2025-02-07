package com.example.service

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.repository.TransactionRepository
import org.http4k.core.*
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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.semconv.SemanticAttributes
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
    private val conversionResponseLens = Body.auto<ApiResponse<CurrencyConversionResponse>>().toLens()

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

        // Start consumer in background thread
        startFraudResultConsumer()
    }

    private fun startFraudResultConsumer() {
        thread(start = true, name = "fraud-result-consumer") {
            try {
                while (true) {
                    val records = consumer.poll(Duration.ofMillis(100))
                    for (record in records) {
                        try {
                            val transactionId = record.key()
                            log.info("Received fraud check result for transaction: {}", transactionId)
                            
                            val updatedTransaction = transactionRepository.updateStatus(
                                transactionId,
                                TransactionStatus.APPROVED
                            )
                            
                            log.info("Updated transaction {} status to APPROVED", transactionId)
                        } catch (e: Exception) {
                            log.error("Error processing fraud result: {}", e.message, e)
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Error in fraud result consumer: {}", e.message, e)
            }
        }
    }

    private val headersSetter = TextMapSetter<MutableList<RecordHeader>> { headers, key, value ->
        requireNotNull(headers) { "Headers list cannot be null" }
        headers.add(RecordHeader(key, value.toByteArray()))
    }

    fun createTransaction(userId: String, amount: BigDecimal, description: String, currency: String = "USD"): Transaction {
        log.info("Creating transaction - user_id: {}, amount: {}, currency: {}", userId, amount, currency)
        
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
            
            val conversionResponse = conversionResponseLens(response)
            conversionResponse.data?.convertedAmount 
                ?: throw RuntimeException("Currency conversion response was empty")
        } else {
            amount
        }

        val transaction = transactionRepository.create(userId, usdAmount, description)
        publishToFraudCheck(transaction)
        return transaction
    }

    private fun publishToFraudCheck(transaction: Transaction) {
        val span = tracer.spanBuilder("publish fraud check request")
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
            .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, FRAUD_CHECK_TOPIC)
            .setAttribute(SemanticAttributes.MESSAGING_OPERATION, "publish")
            .setAttribute("messaging.kafka.client_id", "transaction-service")
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
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Error publishing fraud check request")
        } finally {
            span.end()
        }
    }

    fun getUserTransactions(userId: String): List<Transaction> {
        log.info("Fetching transactions for user_id: {}", userId)
        return transactionRepository.findByUserId(userId)
    }
}

data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)

data class CurrencyConversionResponse(
    val fromCurrency: String,
    val toCurrency: String,
    val amount: BigDecimal,
    val convertedAmount: BigDecimal,
    val rate: BigDecimal
)