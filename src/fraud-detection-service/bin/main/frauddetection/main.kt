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

const val FRAUD_CHECK_TOPIC = "transactions.fraud.check"
const val FRAUD_RESULT_TOPIC = "transactions.fraud.result"
const val GROUP_ID = "fraud-detection"

private val logger = LoggerFactory.getLogger(GROUP_ID)
private val meter = GlobalOpenTelemetry.getMeter("fraud-detection")
private val tracer = GlobalOpenTelemetry.getTracer("fraud-detection")
private val objectMapper = ObjectMapper().apply {
    registerModule(JavaTimeModule())
}

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

            orderCreditReport(userId,10)

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

                // For demo purposes, we'll simulate accuracy based on amount
                // In a real system, this would come from feedback/verification
                fraudCheckAccuracy.record(0.95) // 95% confidence for fraudulent cases
            } else {
                fraudDetectionRate.add(
                    1, Attributes.of(
                        AttributeKey.stringKey("transaction_id"), transactionId,
                        AttributeKey.stringKey("result"), "legitimate"
                    )
                )
                fraudCheckAccuracy.record(0.90) // 90% confidence for legitimate cases
            }
        }
    }  catch (e: Exception) {
        logger.error("Error while processing transaction record with id ${transactionId}", e)
        span.setStatus(StatusCode.ERROR)
        span.recordException(e)
    } finally {
        span.end()
    }
}

private fun orderCreditReport(userId: String, depth: Int) {
    if (depth <= 0) return

    val childSpan = tracer.spanBuilder("orderCreditReport")
        .setAttribute("company.name", "Tom Bombadil Incorporated")
        .startSpan()

    try {
        childSpan.makeCurrent().use { ctx ->
            // Add an event for this branch
            childSpan.addEvent("branchCompanyReportRequested", 
                Attributes.of(
                    AttributeKey.stringKey("companyName"), "Tom Bombadil Incorporated",
                    AttributeKey.stringKey("reportType"), "credit_history"
                )
            )

            // Simulate problematic vendor API call
            Thread.sleep(Random.nextLong(6000, 10000))

            // Recursive call simulating error in report ordering
            if (userId == "fixed_user_3") {
                orderCreditReport(userId, depth - 1)
            }
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
