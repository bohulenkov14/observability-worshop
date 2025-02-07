package com.example.service

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

class KafkaConsumerService(bootstrapServers: String) {
    private val logger = LoggerFactory.getLogger(KafkaConsumerService::class.java)
    private val consumer: KafkaConsumer<String, String>

    init {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            put("group.id", "reconciliation-service")
            put("key.deserializer", StringDeserializer::class.java.name)
            put("value.deserializer", StringDeserializer::class.java.name)
            put("auto.offset.reset", "earliest")
        }

        consumer = KafkaConsumer<String, String>(props)
        consumer.subscribe(listOf("transactions"))
    }

    fun startConsuming() {
        logger.info("Starting Kafka consumer for reconciliation service...")
        
        try {
            while (true) {
                val records = consumer.poll(Duration.ofMillis(100))
                records.forEach { record ->
                    logger.info("Received transaction for reconciliation - Key: ${record.key()}")
                    logger.info("Transaction details: ${record.value()}")
                    // Future: Implement reconciliation logic here
                }
            }
        } catch (e: Exception) {
            logger.error("Error consuming Kafka messages", e)
        } finally {
            consumer.close()
        }
    }
} 