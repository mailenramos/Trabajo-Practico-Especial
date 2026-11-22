# Spec Delta

## MODIFIED Requirements

### Requirement: Limitar la publicación de puertos Docker
Los servicios SHALL usar la red externa de nombre fijo `ticketya-net` (`external: true`, `name: ticketya-net`) y tener healthchecks. El usuario SHALL crear esta red con `docker network create ticketya-net` antes de iniciar Compose. El Compose base SHALL carecer de `ports` para la aplicación y MongoDB; `docker-compose.dev.yml` SHALL publicar solo los puertos acordados para loopback/desarrollo.

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
- **THEN** consulta `/actuator/health`, que informa los componentes y los estados de los circuitos sin detalles internos de componentes

#### Scenario: Circuito abierto sin degradar health general
- **GIVEN** todos los indicadores de salud ajenos a circuitos están `UP` y al menos un circuito está `OPEN`
- **WHEN** Docker consulta `/actuator/health`
- **THEN** responde HTTP `200` con estado general `UP` y permite observar el circuito abierto

#### Scenario: Fallas reales siguen afectando health
- **GIVEN** un indicador de salud ajeno a circuitos está `DOWN`
- **WHEN** se consulta `/actuator/health`
- **THEN** el estado general es `DOWN` y no se oculta la falla por la configuración de circuitos

#### Scenario: Mantener aplicación y rutas internas sin publicación al host
- **GIVEN** se inicia el Compose base sin combinarlo con `docker-compose.dev.yml`
- **WHEN** MS1 queda disponible en Docker
- **THEN** ni MS1 ni Mongo tienen puertos publicados y `/internal/**` y el callback de disponibilidad no quedan expuestos al host

#### Scenario: Publicar los puertos solo con la configuración de desarrollo
- **GIVEN** el usuario inicia MS1 combinando `docker-compose.yml` y `docker-compose.dev.yml`
- **WHEN** la aplicación queda sana
- **THEN** `docker-compose.dev.yml` publica `8081:8081` para MS1 y `127.0.0.1:27017:27017` para Mongo, sin agregar publicaciones al Compose base
