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
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("frauddetection.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("fraud-detection")
    archiveVersion.set("1.0")
    mergeServiceFiles()
}
