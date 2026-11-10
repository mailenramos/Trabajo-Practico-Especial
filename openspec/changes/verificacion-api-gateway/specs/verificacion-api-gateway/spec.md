# Verificación del API Gateway Specification

## Purpose

Definir evidencia automatizada y manual repetible de que el API Gateway de Entrega 1 reenvía las rutas públicas a sus microservicios destino y no publica rutas internas.

## ADDED Requirements

### Requirement: Suite automatizada independiente de los microservicios

El proyecto SHALL proporcionar pruebas unitarias y de integración del Gateway que se puedan ejecutar sin iniciar los microservicios reales ni sus bases de datos.

#### Scenario: Ejecución normal de pruebas

- **WHEN** se ejecutan las pruebas Maven del API Gateway
- **THEN** las pruebas unitarias validan de forma aislada los comportamientos propios del Gateway
- **AND** las pruebas de integración usan destinos falsos para verificar el reenvío HTTP
- **AND** la ejecución no requiere MS1–MS5, bases de datos ni Docker

#### Scenario: Verificar operación pública

- **WHEN** una prueba envía al Gateway una operación pública documentada
- **THEN** verifica que solo el servicio dueño recibe la solicitud
- **AND** verifica la preservación del método, path, query, body y headers relevantes
- **AND** verifica que status y cuerpo de respuesta se devuelven al cliente

#### Scenario: Verificar operación excluida

- **WHEN** una prueba envía una ruta interna, desconocida o un método no permitido
- **THEN** verifica que ningún destino backend recibe la solicitud
- **AND** verifica que el Gateway no informa éxito

#### Scenario: Destino no disponible

- **WHEN** una prueba invoca una ruta pública cuyo destino no está disponible
- **THEN** verifica una respuesta HTTP 503 del Gateway
- **AND** verifica que la respuesta no simula un resultado exitoso

### Requirement: Colección Postman para integración local

El proyecto SHALL incluir una colección Postman y un environment sin secretos para verificar las rutas públicas del Gateway en `http://localhost:8086` contra microservicios locales.

#### Scenario: Consulta de eventos a través del Gateway

- **WHEN** el usuario ejecuta una consulta pública de eventos en Postman con los servicios disponibles
- **THEN** la solicitud usa el Gateway como único destino HTTP del cliente
- **AND** permite comprobar la respuesta entregada por Eventos/Lugares

#### Scenario: Reserva con datos locales disponibles

- **WHEN** el usuario completa las variables de un evento publicado y disponible y ejecuta el request de reserva
- **THEN** Postman envía la operación pública de reserva por el Gateway
- **AND** muestra la respuesta real de Reservas sin ocultar errores de validación o disponibilidad

#### Scenario: Compra y consulta de entradas

- **WHEN** están disponibles la reserva y las dependencias necesarias de Compras, Pagos y Entradas
- **THEN** la colección permite ejecutar el flujo público de compra/pago y consultar sus resultados mediante el Gateway
- **AND** no invoca Pagos directamente desde la colección

#### Scenario: Rutas internas probadas manualmente

- **WHEN** el usuario ejecuta requests de verificación para rutas internas o métodos no publicados
- **THEN** el Gateway no devuelve un resultado exitoso del microservicio
- **AND** la documentación indica que estas requests no deben atravesar la allowlist

#### Scenario: Diagnóstico de CORS

- **WHEN** el usuario ejecuta las pruebas de integración CORS o prueba desde un navegador
- **THEN** verifica el origen permitido y la ausencia de autorización CORS para un origen no configurado
- **AND** la documentación aclara que Postman no aplica ni verifica las restricciones CORS del navegador
