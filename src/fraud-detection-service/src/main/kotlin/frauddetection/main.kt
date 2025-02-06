/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package frauddetection

import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration.ofMillis
import java.util.*
import kotlin.system.exitProcess

const val TOPIC = "transactions"
const val GROUP_ID = "fraud-detection"

private val logger = LoggerFactory.getLogger(GROUP_ID)

fun main() {
    val props = Properties()
    props[KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[GROUP_ID_CONFIG] = GROUP_ID
    props[AUTO_OFFSET_RESET_CONFIG] = "earliest"  // Start from the beginning if no offset is found
    
    val bootstrapServers = System.getenv("KAFKA_ADDR")
    if (bootstrapServers == null) {
        logger.error("KAFKA_ADDR is not supplied")
        exitProcess(1)
    }
    props[BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers

    val consumer = KafkaConsumer<String, String>(props).apply {
        subscribe(listOf(TOPIC))
    }

    logger.info("Starting fraud detection service, listening for transactions...")
    
    consumer.use {
        while (true) {
            consumer
                .poll(ofMillis(100))
                .forEach { record ->
                    logger.info("Processing transaction - ID: ${record.key()}")
                    logger.info("Transaction details: ${record.value()}")
                }
        }
    }
}
