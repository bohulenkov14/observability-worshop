FROM gradle:8.5-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle clean shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app

COPY --from=build /home/gradle/src/build/libs/PublicApi.jar /app/public-api.jar
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/public-api.jar"] 