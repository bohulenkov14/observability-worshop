# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

FROM gradle:8.5-jdk21 AS build
COPY . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/fraud-detection-1.0-all.jar /app/fraud-detection.jar
COPY --from=build /home/gradle/src/src/main/resources/logback.xml /app/logback.xml

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/fraud-detection.jar"]
