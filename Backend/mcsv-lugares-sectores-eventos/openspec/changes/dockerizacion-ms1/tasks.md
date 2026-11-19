# Tasks

- [x] 1. Confirmados Reservas (`reservas:8080`), Fila virtual (`fila-virtual:8088`) y la red `ticketya-net` externa. Pendientes, no bloqueantes: nombre y puerto de Compras (default provisional `compras:8083`) y ruteo/healthcheck del Gateway, a ajustar en `COMPRAS_BASE_URL` y el README.
- [x] 2. Crear el Dockerfile multi-etapa con Maven/JDK 21, `-DskipTests`, cache de dependencias, runtime `eclipse-temurin:21-jre-alpine` con `wget` o `curl` y usuario no root; cambiar el default local de `MONGODB_URI` a `mongodb://localhost:27017/ticketya_lugares?directConnection=true`.
- [x] 3. Actualizar el Compose local de MS1: servicio `lugares`, red `ticketya-net`, Mongo replica set `rs0` con miembro `mongodb:27017`, volumen persistente, inicialización y healthcheck; `SPRING_PROFILES_ACTIVE=docker` por defecto, URLs `reservas`/`compras`, healthcheck Actuator, `depends_on: service_healthy` y sin `ports`.
- [x] 4. Verificar que `docker-compose.dev.yml` publica solo `8081:8081` y `127.0.0.1:27017:27017`; el Compose base no tiene `ports`.
- [x] 5. Actualizar README con ejecución aislada, integración con el Compose general, variables de entorno, URI host `directConnection=true` y advertencia de que `docker compose down -v` elimina permanentemente los datos del volumen al reinicializar un `rs0` legacy.
- [x] 6. Validar Dockerización y pruebas sin modificar comportamiento funcional:
  - [x] Verificar la sintaxis del Compose base y combinado con el archivo dev; verificar que el Compose base no publique puertos y que el dev publique solo `8081:8081` y `127.0.0.1:27017:27017`.
  - [x] Construir la imagen y verificar sus metadatos de runtime (usuario no root).
  - [x] Verificar contenedores healthy, healthcheck Actuator sin detalles, perfil `docker` sin datos de ejemplo, usuario `app`, red y puertos; probar backfill y cancelación con Compras disponible/no disponible y reintento mediante Postman 09, 10 y 11.
  - [x] Verificar el modo host con `directConnection=true` y Postman 00 y 09.
  - [x] Ejecutar `mvnw.cmd test`; la suite pasó sin fallos.
