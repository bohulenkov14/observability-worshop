package com.example.adapter

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.application.port.ReconciliationStoragePort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import kotlin.concurrent.thread

class KafkaTransactionConsumer(
    private val reconciliationStoragePort: ReconciliationStoragePort,
    private val bootstrapServers: String = "kafka:9092",
    private val groupId: String = "reconciliation-service"
) {
    private val log = LoggerFactory.getLogger(KafkaTransactionConsumer::class.java)
    private val consumer: KafkaConsumer<String, String>
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerKotlinModule()
    }

    companion object {
        const val FRAUD_CHECK_TOPIC = "transactions.fraud.check"
        const val FRAUD_RESULT_TOPIC = "transactions.fraud.result"
    }

    init {
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        consumer = KafkaConsumer<String, String>(consumerProps).apply {
            subscribe(listOf(FRAUD_CHECK_TOPIC, FRAUD_RESULT_TOPIC))
        }

        startConsumer()
    }

    private fun startConsumer() {
        thread(start = true, name = "transaction-consumer") {
            consumer.use {
                try {
                    while (true) {
                        val records = consumer.poll(Duration.ofMillis(100))
                        for (record in records) {
                            try {
                                log.info("Received message from topic: {}, key: {}", record.topic(), record.key())
                                val transaction = objectMapper.readValue(record.value(), Transaction::class.java)
                                log.info(
                                    "Processing transaction - ID: {}, Type: {}, Amount: {}, Status: {}", 
                                    transaction.id,
                                    transaction.type,
                                    transaction.amount,
                                    transaction.status
                                )

                                when (record.topic()) {
                                    FRAUD_CHECK_TOPIC -> {
                                        log.info("Recording new transaction for reconciliation: {}", transaction.id)
                                        reconciliationStoragePort.recordTransaction(transaction)
                                    }
                                    FRAUD_RESULT_TOPIC -> {
                                        log.info("Updating transaction status after fraud check: {}", transaction.id)
                                        reconciliationStoragePort.updateTransactionStatus(
                                            transaction.id,
                                            TransactionStatus.PENDING_BALANCE_DEDUCTION
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                log.error(
                                    "Error processing record from topic {}: {}", 
                                    record.topic(), 
                                    e.message, 
                                    e
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("Fatal error in consumer: {}", e.message, e)
                }
            }
        }
    }
} 