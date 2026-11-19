## ADDED Requirements

### Requirement: Construir y ejecutar MS1 como contenedor seguro
La imagen de MS1 SHALL construirse en una etapa Maven/JDK 21 y ejecutarse en una imagen runtime JRE 21, bajo un usuario no root.

#### Scenario: Ejecutar MS1 como usuario no root sobre JRE 21
- **GIVEN** se inicia la imagen runtime de MS1
- **WHEN** MS1 inicia
- **THEN** el proceso Java corre como usuario no root y usa una runtime JRE 21

#### Scenario: Construir la imagen de MS1
- **GIVEN** el contexto contiene el wrapper Maven y el proyecto Java 21
- **WHEN** se construye la imagen
- **THEN** la etapa de build usa Maven/JDK 21 y ejecuta el empaquetado con `-DskipTests`

### Requirement: Configurar MS1 en contenedores por variables de entorno
La configuración del contenedor SHALL usar las variables existentes `MONGODB_URI`, `RESERVAS_BASE_URL`, `COMPRAS_BASE_URL`, `OUTBOX_DISPATCHER_INTERVAL`, `EVENTOS_FINALIZACION_INTERVAL`, `SPRING_PROFILES_ACTIVE` y `PORT`. Las URLs entre servicios Docker SHALL usar nombres DNS de servicios, nunca `localhost`. El perfil predeterminado SHALL evitar datos de ejemplo.

#### Scenario: Omitir los datos de ejemplo por defecto en Docker
- **GIVEN** Compose establece `SPRING_PROFILES_ACTIVE=docker` por defecto
- **WHEN** la aplicación arranca
- **THEN** el perfil `docker` no carga datos de ejemplo y MS1 escucha en `PORT` (por defecto `8081`)

#### Scenario: Resolver los servicios por nombre DNS de Docker
- **GIVEN** MS1 se inicia en `ticketya-net`
- **WHEN** se configuran `RESERVAS_BASE_URL` y `COMPRAS_BASE_URL` sin valores explícitos
- **THEN** las URLs por defecto son `http://reservas:8080` y `http://compras:8083`, sin `localhost`

#### Scenario: Conectar al Mongo de Docker desde el host
- **GIVEN** se ejecuta MS1 en el host con `mvn spring-boot:run`
- **WHEN** no se sobrescribe `MONGODB_URI`
- **THEN** el default es `mongodb://localhost:27017/ticketya_lugares?directConnection=true`, sin opción `replicaSet`

### Requirement: Ejecutar MongoDB como replica set para MS1
El Compose SHALL ejecutar MongoDB como un replica set `rs0` de un solo nodo, con volumen persistente e inicialización automática. El miembro SHALL anunciarse como `mongodb:27017`; `lugares` SHALL esperar con `depends_on: service_healthy`.

#### Scenario: Esperar a MongoDB sano antes de iniciar la aplicación
- **GIVEN** MongoDB inicia o todavía está eligiendo el primario de `rs0`
- **WHEN** Compose inicia MS1
- **THEN** MS1 permanece en espera hasta que el healthcheck de MongoDB confirme que el replica set está operativo

### Requirement: Limitar la publicación de puertos Docker
Los servicios SHALL usar la red externa de nombre fijo `ticketya-net` (`external: true`, `name: ticketya-net`) y tener healthchecks. El usuario SHALL crear esta red con `docker network create ticketya-net` antes de iniciar Compose. El Compose base SHALL carecer de `ports` para la aplicación y MongoDB; el override de desarrollo SHALL publicar solo los puertos acordados para loopback/desarrollo.

#### Scenario: Conectar a la red externa compartida
- **GIVEN** la red `ticketya-net` fue creada con `docker network create ticketya-net`
- **WHEN** se inicia el Compose de MS1
- **THEN** `lugares` y `mongodb` se conectan a esa red existente sin crear una red por proyecto

#### Scenario: Integrar MS1 desde el Compose general
- **GIVEN** el Compose general también está conectado a la red externa `ticketya-net`
- **WHEN** otro servicio accede a MS1 por la red Docker
- **THEN** lo resuelve como `lugares` en el puerto interno `8081`, sin necesitar puertos publicados al host

#### Scenario: Comprobar la salud de la aplicación sin mostrar detalles
- **GIVEN** la aplicación `lugares` está en ejecución
- **WHEN** se ejecuta su healthcheck
- **THEN** consulta `/actuator/health` y la respuesta informa el estado sin detalles de componentes

#### Scenario: Mantener aplicación y rutas internas sin publicación al host
- **GIVEN** se inicia el Compose base sin combinarlo con `docker-compose.dev.yml`
- **WHEN** MS1 queda disponible en Docker
- **THEN** ni MS1 ni Mongo tienen puertos publicados y `/internal/**` y el callback de disponibilidad no quedan expuestos al host

#### Scenario: Publicar el puerto solo con el override de desarrollo
- **GIVEN** el usuario inicia MS1 combinando `docker-compose.yml` y `docker-compose.dev.yml`
- **WHEN** la aplicación queda sana
- **THEN** el override publica `8081` para MS1 y `127.0.0.1:27017:27017` para Mongo, sin agregar publicaciones al Compose base

### Requirement: Mantener el desarrollo en el host compatible con el replica set
El despliegue SHALL conservar la ejecución de MS1 desde el host con `mvn spring-boot:run`. El default local de `MONGODB_URI` SHALL usar una conexión directa a `localhost` sin opción `replicaSet`; la URI interna del Compose se configura por separado para `mongodb:27017` y `rs0`.

#### Scenario: Ejecutar MS1 desde el host con MongoDB en Docker
- **GIVEN** MongoDB está sano en Docker y anuncia `mongodb:27017`
- **WHEN** el desarrollador ejecuta MS1 en el host
- **THEN** puede conectarse a MongoDB por el puerto publicado en el override usando `mongodb://localhost:27017/ticketya_lugares?directConnection=true` y conserva el perfil de desarrollo local existente
