/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package frauddetection

import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration.ofMillis
import java.util.*
import kotlin.system.exitProcess
import kotlin.random.Random
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.http4k.core.*
import org.http4k.client.OkHttp
import org.http4k.format.Jackson.auto
import java.time.Instant
import java.math.BigDecimal

const val FRAUD_CHECK_TOPIC = "transactions.fraud.check"
const val FRAUD_RESULT_TOPIC = "transactions.fraud.result"
const val GROUP_ID = "fraud-detection"
const val PROBLEMATIC_EXTERNAL_ID = "ext_id_3"
const val USER_SERVICE_URL = "http://user-service:8080"

private val logger = LoggerFactory.getLogger(GROUP_ID)
private val meter = GlobalOpenTelemetry.getMeter("fraud-detection")
private val tracer = GlobalOpenTelemetry.getTracer("fraud-detection")
private val objectMapper = ObjectMapper().apply {
    registerModule(JavaTimeModule())
}
private val httpClient = OkHttp()

// Business metrics
private val fraudCheckDuration: DoubleHistogram = meter.histogramBuilder("fraud_check_duration_ms")
    .setDescription("Time taken for fraud check to complete")
    .setUnit("milliseconds")
    .build()

// Fraud Detection Metrics
private val fraudDetectionRate = meter.counterBuilder("fraud_detection_total")
    .setDescription("Number of transactions flagged as fraudulent")
    .setUnit("1")
    .build()

private val fraudCheckAccuracy = meter.histogramBuilder("fraud_check_accuracy")
    .setDescription("Accuracy of fraud detection (false positives/negatives)")
    .setUnit("1")
    .build()

// Response models for user service
data class ApiResponse<T>(val status: String, val data: T? = null, val message: String? = null)
data class UserInfo(
    val id: String,
    val username: String,
    val email: String,
    val externalId: String,
    val balance: BigDecimal,
    val isFrozen: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

private fun getUserInfo(userId: String): UserInfo? {
    return try {
        val response = httpClient(
            Request(Method.GET, "$USER_SERVICE_URL/user/$userId")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
        )

        if (response.status != Status.OK) {
            logger.error("Failed to get user info. Status: {}", response.status)
            return null
        }

        val apiResponse = Body.auto<ApiResponse<UserInfo>>().toLens()(response)
        apiResponse.data
    } catch (e: Exception) {
        logger.error("Error fetching user info: {}", e.message)
        null
    }
}

fun main() {
    val consumerProps = Properties().apply {
        put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(GROUP_ID_CONFIG, GROUP_ID)
        put(AUTO_OFFSET_RESET_CONFIG, "earliest")
    }
    
    val producerProps = Properties().apply {
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
    }
    
    val bootstrapServers = System.getenv("KAFKA_ADDR")
    if (bootstrapServers == null) {
        logger.error("KAFKA_ADDR is not supplied")
        exitProcess(1)
    }
    consumerProps[BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    producerProps[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers

    val consumer = KafkaConsumer<String, String>(consumerProps).apply {
        subscribe(listOf(FRAUD_CHECK_TOPIC))
    }

    val producer = KafkaProducer<String, String>(producerProps)

    logger.info("Starting fraud detection service, listening for transactions...")
    
    consumer.use { consumer ->
        while (true) {
            consumer
                .poll(ofMillis(100))
                .forEach { record ->
                    val transactionId = record.key()
                    val transactionJson = record.value()
                    val startTime = System.currentTimeMillis()
                    
                    logger.info("Processing transaction for fraud check - ID: {}", transactionId)
                    logger.info("Transaction details: {}", transactionJson)

                    executeFraudCheck(startTime, transactionId,  transactionJson)

                    // Publish the result
                    val result = ProducerRecord(
                        FRAUD_RESULT_TOPIC,
                        transactionId,
                        transactionJson
                    )
                    
                    producer.send(result) { metadata, exception ->
                        if (exception != null) {
                            logger.error("Failed to publish fraud check result: {}", exception.message)
                        } else {
                            logger.info(
                                "Published fraud check result - topic: {}, partition: {}, offset: {}", 
                                metadata.topic(), 
                                metadata.partition(), 
                                metadata.offset()
                            )
                        }
                    }
                }
        }
    }
}

private fun executeFraudCheck(startTime: Long, transactionId: String?, transactionJson: String) {
    val span = tracer.spanBuilder("executeFraudCheck")
        .setAttribute("record.transactionId", transactionId)
        .startSpan()
    try {
        span.makeCurrent().use { ctx ->
            // Parse transaction to get userId
            val transaction = objectMapper.readTree(transactionJson)
            val userId = transaction.get("userId").asText()
            span.setAttribute("transaction.userId", userId)

            // Get user details from user service
            val userInfo = getUserInfo(userId)
            if (userInfo != null) {
                span.setAttribute("user.id", userInfo.id)
                span.setAttribute("user.username", userInfo.username)
                span.setAttribute("user.email", userInfo.email)
                span.setAttribute("user.externalId", userInfo.externalId)
                span.setAttribute("user.balance", userInfo.balance.toDouble())
                span.setAttribute("user.isFrozen", userInfo.isFrozen)
                span.setAttribute("user.createdAt", userInfo.createdAt.toString())
                span.setAttribute("user.updatedAt", userInfo.updatedAt.toString())
                
                // Check if this is our problematic user
                orderCreditReport(userInfo.externalId)
            }

            // Mimic some CPU-intensive fraud detection work
            val isFraudulent = performDummyFraudCheck()

            // Record the fraud check duration
            val duration = System.currentTimeMillis() - startTime
            fraudCheckDuration.record(duration.toDouble())

            // Record fraud detection metrics
            if (isFraudulent) {
                fraudDetectionRate.add(
                    1, Attributes.of(
                        AttributeKey.stringKey("transaction_id"), transactionId,
                        AttributeKey.stringKey("result"), "fraudulent"
                    )
                )
                fraudCheckAccuracy.record(0.95)
            } else {
                fraudDetectionRate.add(
                    1, Attributes.of(
                        AttributeKey.stringKey("transaction_id"), transactionId,
                        AttributeKey.stringKey("result"), "legitimate"
                    )
                )
                fraudCheckAccuracy.record(0.90)
            }
        }
    } catch (e: Exception) {
        logger.error("Error while processing transaction record with id ${transactionId}", e)
        span.setStatus(StatusCode.ERROR)
        span.recordException(e)
    } finally {
        span.end()
    }
}

private fun orderCreditReport(externalId: String) {
    val childSpan = tracer.spanBuilder("orderCreditReport")
        .setAttribute("user.externalId", externalId)
        .setAttribute("company.name", "Tom Bombadil Incorporated")
        .startSpan()

    try {
        childSpan.makeCurrent().use { ctx ->
            childSpan.addEvent("branchCompanyReportRequested", 
                Attributes.of(
                    AttributeKey.stringKey("companyName"), "Tom Bombadil Incorporated",
                    AttributeKey.stringKey("reportType"), "credit_history"
                )
            )

            // Simulate problematic vendor API call
            Thread.sleep(Random.nextLong(300))
        }
    } finally {
        childSpan.end()
    }
}

private fun performDummyFraudCheck(): Boolean {
    // Simulate CPU-intensive work
    var result = 0.0
    for (i in 1..100000) {
        result += Math.sin(i.toDouble())
    }
    
    // Add some random delay between 100ms and 500ms
    Thread.sleep(Random.nextLong(100, 500))
    
    // Randomly determine if transaction is fraudulent (10% chance)
    return Random.nextDouble() < 0.1
}
