FROM gradle:8.5-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app && \
    apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /home/gradle/src/build/libs/CurrencyExchangeService.jar /app/currency-exchange-service.jar
COPY --from=build /home/gradle/src/src/main/resources/application.yaml /app/application.yaml
COPY --from=build /home/gradle/src/src/main/resources/logback.xml /app/logback.xml
ENV LOG4J_FORMAT_MSG_NO_LOOKUPS=true
ENTRYPOINT ["java", "-Dlog4j2.configurationFile=/app/log4j2.xml", "-Dconfig.file=/app/application.yaml", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/currency-exchange-service.jar"]