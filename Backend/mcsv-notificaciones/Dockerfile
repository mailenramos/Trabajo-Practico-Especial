# Etapa 1: compila el jar (sin correr los tests; se corren aparte con ./mvnw test)
FROM maven:3.9-eclipse-temurin-21 AS compilacion
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
RUN mvn -B -q package -DskipTests

# Etapa 2: imagen final, solo con el JRE y el jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system ticketya && useradd --system --gid ticketya ticketya
COPY --from=compilacion /app/target/notificaciones-*.jar app.jar
USER ticketya
# Puerto a confirmar por el grupo (se cambia con PUERTO)
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
