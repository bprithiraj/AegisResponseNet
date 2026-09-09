FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre
RUN groupadd --system aegis && useradd --system --gid aegis aegis
WORKDIR /app
COPY --from=build /build/target/aegis-responsenet-1.0.0.jar app.jar
USER aegis
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
