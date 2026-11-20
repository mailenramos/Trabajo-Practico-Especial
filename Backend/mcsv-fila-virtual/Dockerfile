FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline -DskipTests
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system ticketya && useradd --system --gid ticketya --home-dir /app --no-create-home ticketya
COPY --from=build --chown=ticketya:ticketya /build/target/ms-fila-virtual-0.0.1-SNAPSHOT.jar /app/app.jar
USER ticketya
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
