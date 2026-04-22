FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /workspace/target/netsentinel-0.1.0.jar /app/netsentinel.jar
COPY config/netsentinel.json /app/config/netsentinel.json
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "/app/netsentinel.jar", "/app/config/netsentinel.json"]
