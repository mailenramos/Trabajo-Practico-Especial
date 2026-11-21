# MS6: Gestión de usuarios y autenticación (JWT).
# La configuración que cambia por entorno y los secretos se pasan al ejecutar el contenedor, nunca
# se copian a la imagen: DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, ADMIN_EMAIL, ADMIN_PASSWORD,
# SERVICIO_TOKEN (ver .env.example).

# Etapa 1: compila el jar (los tests se corren aparte con ./mvnw test)
FROM maven:3.9-eclipse-temurin-21 AS compilacion
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
RUN mvn -B -q package -DskipTests

# Etapa 2: imagen final, solo con el JRE y el jar
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl solo para el healthcheck de docker-compose (/actuator/health)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system ticketya && useradd --system --gid ticketya ticketya
COPY --from=compilacion /app/target/mcsv-usuarios-autenticacion-*.jar app.jar
USER ticketya
# Solo documenta el puerto: no se publica fuera de la red de Docker, lo alcanza el Gateway
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
