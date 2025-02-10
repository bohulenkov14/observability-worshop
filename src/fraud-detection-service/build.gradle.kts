plugins {
    kotlin("jvm") version "1.9.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.apache.kafka:kafka-clients:3.6.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.opentelemetry:opentelemetry-api:1.46.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.46.0")
    implementation("io.opentelemetry:opentelemetry-extension-annotations:1.18.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}

application {
    mainClass.set("frauddetection.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("fraud-detection")
    archiveVersion.set("1.0")
    mergeServiceFiles()
}
