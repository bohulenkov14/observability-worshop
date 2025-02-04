FROM gradle:8.5-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app

COPY --from=build /home/gradle/src/build/libs/PublicApi.jar /app/public-api.jar
COPY src/main/resources/logback.xml /app/logback.xml
ENV LOG4J_FORMAT_MSG_NO_LOOKUPS=true
ENTRYPOINT ["java", "-Dlog4j2.configurationFile=/app/log4j2.xml", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/public-api.jar"]