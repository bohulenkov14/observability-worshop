package com.example.service

import com.example.domain.Transaction
import com.example.domain.TransactionStatus
import com.example.repository.ReconciliationRepository
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

class KafkaConsumerService(
    private val reconciliationRepository: ReconciliationRepository
) {
    private val log = LoggerFactory.getLogger(KafkaConsumerService::class.java)
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
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092")
            put(ConsumerConfig.GROUP_ID_CONFIG, "reconciliation-service")
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
                                    reconciliationRepository.recordTransaction(transaction)
                                }
                                FRAUD_RESULT_TOPIC -> {
                                    log.info("Updating transaction status after fraud check: {}", transaction.id)
                                    reconciliationRepository.updateTransactionStatus(
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