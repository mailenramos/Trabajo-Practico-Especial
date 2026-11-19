# Design

## Evidencia del repositorio

- MS1 usa Spring Boot 3.4.4 y Java 21 en `pom.xml`; incluye `spring-boot-starter-actuator`.
- `application.yml` permite configurar `MONGODB_URI`, `RESERVAS_BASE_URL`, `COMPRAS_BASE_URL`, `OUTBOX_DISPATCHER_INTERVAL`, `EVENTOS_FINALIZACION_INTERVAL`, `SPRING_PROFILES_ACTIVE` y `PORT`. El default de host de `MONGODB_URI` es `mongodb://localhost:27017/ticketya_lugares?directConnection=true`.
- El perfil predeterminado es `dev`; `DevSampleDataInitializer` tiene `@Profile("dev")` y carga datos de ejemplo.
- El Compose de MS1 define los servicios `lugares` y `mongodb`; no publica puertos en la configuración base, usa un volumen persistente, inicializa `rs0` anunciando `mongodb:27017` y conecta ambos servicios a la red externa `ticketya-net`.
- Actuator ya está incluido, por lo que se puede comprobar la salud HTTP en `/actuator/health` sin agregar un endpoint funcional nuevo.
- En el workspace solo se encontró el Compose de MS1; no hay un Compose general del proyecto ni archivos Compose de MS2/MS3 para confirmar nombres de servicio.

## Diseño propuesto

### Imagen de MS1

- Construir con una imagen Maven que incluya JDK 21. Copiar primero `pom.xml`, `mvnw` y `.mvn/`, precargar dependencias para aprovechar la cache de capas y empaquetar con `./mvnw -B -DskipTests package`.
- Copiar únicamente el JAR ejecutable a `eclipse-temurin:21-jre-alpine`; incluir `wget` o `curl` para el healthcheck HTTP.
- Ejecutar Java con un usuario sin privilegios y sin publicar puertos en la imagen.
- Configurar la comprobación de salud mediante `wget` a `/actuator/health`, con un `start_period` de 40 segundos; `management.endpoint.health.show-details` se establece explícitamente en `never`. El mecanismo de prueba debe estar disponible en la imagen runtime y no requerir privilegios root.

### Configuración del contenedor

El Compose de MS1 inyectará exclusivamente variables de entorno ya existentes:

| Variable | Configuración dentro de Docker |
|---|---|
| `MONGODB_URI` | En Compose: `mongodb://mongodb:27017/ticketya_lugares?replicaSet=rs0`. El default de host en `application.yml` será `mongodb://localhost:27017/ticketya_lugares?directConnection=true`. |
| `RESERVAS_BASE_URL` | Sustitución Compose `${RESERVAS_BASE_URL:-http://reservas:8080}`; nombre DNS de Docker, nunca `localhost`. |
| `COMPRAS_BASE_URL` | Sustitución Compose `${COMPRAS_BASE_URL:-http://compras:8083}`; nombre DNS de Docker, nunca `localhost`. |
| `OUTBOX_DISPATCHER_INTERVAL` | Intervalo actual del dispatcher, default `60s`. |
| `EVENTOS_FINALIZACION_INTERVAL` | Intervalo actual de finalización, default `60s`. |
| `SPRING_PROFILES_ACTIVE` | Compose usa `${SPRING_PROFILES_ACTIVE:-docker}` para seleccionar explícitamente el perfil `docker` por defecto y no cargar datos de ejemplo; un valor explícito puede sobrescribirlo. |
| `PORT` | Puerto interno de la aplicación, default `8081`. |

El default de `MONGODB_URI` en `application.yml` se cambia a `mongodb://localhost:27017/ticketya_lugares?directConnection=true`, que conserva `mvn spring-boot:run` desde el host sin discovery del miembro Docker anunciado como `mongodb:27017`. Las pruebas con Testcontainers conservan su URI de replica set existente.

### Compose local de MS1

- El servicio `mongodb` mantiene la imagen MongoDB actual y el volumen nombrado persistente; no publica puertos en el Compose base.
- La inicialización automática de `rs0` debe anunciar `mongodb:27017`, no `localhost`.
- El healthcheck de Mongo debe asegurar que el replica set está inicializado y que el nodo está disponible como primario; no debe marcar sano solo porque `mongod` acepta conexiones.
- El servicio de MS1 se llama `lugares`, usa las variables de entorno descritas, depende de `mongodb` con `condition: service_healthy` y tiene un healthcheck HTTP sobre Actuator.
- Ambos servicios se conectan a la red Docker externa de nombre fijo `ticketya-net`, declarada con `external: true` y `name: ticketya-net`. Compose no crea esta red; antes de iniciar los servicios se crea una vez con `docker network create ticketya-net`. El Compose base no declara `ports`, por lo que ni la aplicación ni Mongo quedan publicados al host.
- El archivo explícito `docker-compose.dev.yml` para desarrollo publica solo `8081:8081` para MS1 y `127.0.0.1:27017:27017` para Mongo; se combina mediante `docker compose -f docker-compose.yml -f docker-compose.dev.yml`.
- El acceso externo a la aplicación queda reservado a la red Docker; no se agrega una publicación en el Gateway de `/internal/**` ni del callback de disponibilidad.

### Uso con otros servicios

La integración se hará conectando los servicios y el Gateway a `ticketya-net`, con resolución DNS por nombre de servicio. El contrato de MS1 para el Compose general es la red externa `ticketya-net`, el servicio DNS `lugares`, el puerto interno `8081` y ningún puerto publicado por el Compose base. Los servicios vecinos previstos son `reservas:8080`, `compras:8083` (provisional) y `fila-virtual:8088`. La ruta del Compose general y el comando exacto para combinarlo con el Compose de MS1 quedan pendientes de confirmar con el grupo. No se modificarán archivos Compose de otros servicios.

## Decisiones

1. Reutilizar Actuator y `/actuator/health`; no agregar un endpoint de salud de negocio.
2. Cambiar el default local de `MONGODB_URI` a `mongodb://localhost:27017/ticketya_lugares?directConnection=true`; Compose sobrescribe la URI con `mongodb:27017` y `replicaSet=rs0`.
3. Compose asigna explícitamente `SPRING_PROFILES_ACTIVE=docker` salvo que el usuario establezca otro valor; el perfil del host permanece `dev`.
4. La aplicación y Mongo no tendrán `ports` en el Compose base; `docker-compose.dev.yml` publica solo `8081:8081` y `127.0.0.1:27017:27017`.
5. Compose usa la red externa `ticketya-net`; el usuario la crea antes de levantar servicios con `docker network create ticketya-net`.
5. Usar `eclipse-temurin:21-jre-alpine` como runtime con `wget` o `curl` disponible para la comprobación de salud.
6. La identidad del miembro MongoDB dentro del replica set será `mongodb:27017`; el acceso desde el host se documentará con `directConnection=true`.
7. No cambiar Compose general de otros servicios.

## Preguntas abiertas

1. Los nombres de servicio de MS2 y MS3 y la ruta del Compose general deben confirmarse con el grupo. La implementación puede comenzar usando `reservas` y `compras` como defaults no bloqueantes y aceptar sustitución mediante las variables existentes `RESERVAS_BASE_URL` y `COMPRAS_BASE_URL`.

## Migración del volumen local existente

El volumen existente puede conservar una configuración de `rs0` que anuncia `localhost:27017`. El README documentará que, si se elige reinicializarlo para cambiar el miembro a `mongodb:27017`, `docker compose down -v` borra todos los datos del volumen; advertirá respaldar o exportar los datos antes de ejecutar ese comando. No se borrarán volúmenes automáticamente durante el arranque.
