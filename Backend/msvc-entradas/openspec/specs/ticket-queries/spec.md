# Especificación de consultas y API de entradas

## Purpose
Registrar los endpoints actuales de consulta de entradas, las comprobaciones de titularidad implementadas, el comportamiento de paginación y la separación entre rutas públicas e internas.

## Status
- **Implementado y probado con tests unitarios del servicio:** filtros/orden de consultas paginadas y denegación de una consulta individual cuando la entrada pertenece a otro cliente.
- **Implementado en controller/servicio, pero sin tests de integración HTTP:** vinculación de rutas, encabezados requeridos, códigos de estado HTTP y serialización de respuestas.
- **Pendiente:** acceso según roles, comprobación de titularidad de eventos por organizadores y autenticación/autorización de rutas internas.

## Requirements

### Requirement: Listar entradas del cliente identificado
La operación pública de listado MUST limitar los resultados al ID de cliente recibido, filtrar opcionalmente por estado e ID de evento, ordenar por fecha de creación descendente y devolver los metadatos de paginación.

#### Scenario: Listar entradas con filtros y paginación
- **WHEN** la solicitud incluye un ID de cliente, filtros opcionales de estado/evento y parámetros de página válidos
- **THEN** el servicio consulta solo las entradas de ese cliente, aplica los filtros recibidos, ordena `creadoEl` de forma descendente y mapea el contenido y los totales de página a la respuesta

#### Scenario: Límites de paginación del endpoint de listado
- **WHEN** se invoca el endpoint público de listado
- **THEN** `page` tiene valor predeterminado 0 y mínimo 0, y `size` tiene valor predeterminado 20 y un rango permitido de 1 a 100

### Requirement: Restringir la consulta pública individual al cliente titular
La operación pública de consulta individual MUST devolver la entrada solo cuando su `idCliente` coincide con el valor `X-User-Id` de la solicitud; de lo contrario, MUST generar un error de acceso denegado.

#### Scenario: Un cliente solicita la entrada de otro cliente
- **WHEN** la entrada solicitada pertenece a un ID de cliente diferente
- **THEN** el servicio genera `AccesoDenegadoException`

### Requirement: Exponer las rutas implementadas de consulta y operación interna
El controller MUST exponer rutas públicas para listar y consultar las entradas de un cliente, y rutas internas para emisión, consulta interna, reintegro/devolución individual y reintegro de todas las entradas de un evento.

#### Scenario: Falta el encabezado de identidad requerido
- **WHEN** se llama a cualquiera de las consultas públicas sin `X-User-Id`
- **THEN** Spring informa que falta el encabezado y el manejador de excepciones lo mapea a HTTP 401 con código `FALTA_IDENTIFICACION`

#### Scenario: Rutas internas
- **WHEN** un consumidor invoca una ruta `/internal`
- **THEN** el controller delega la operación al servicio correspondiente; el código actual no autentica ni autoriza al consumidor interno

## Implemented Route Inventory
| Método | Ruta | Comportamiento actual |
|---|---|---|
| GET | `/entradas` | Listado paginado limitado al cliente; `estado` e `idEvento` opcionales |
| GET | `/entradas/{id}` | Detalle de una entrada limitado al cliente |
| POST | `/internal/entradas/emision` | Emite entradas; devuelve 201 si es nueva y 200 en reintento idempotente |
| GET | `/internal/entradas/{id}` | Detalle interno de una entrada |
| POST | `/internal/entradas/{id}/reintegro` | Cambia la entrada a `EN_REINTEGRO` |
| POST | `/internal/entradas/{id}/devolucion` | Cambia la entrada a `DEVUELTA` |
| POST | `/internal/eventos/{idEvento}/entradas/reintegro` | Cambia a `EN_REINTEGRO` las entradas vigentes del evento |

## Pending and Not Established
- `X-User-Id` se usa para limitar consultas por cliente, pero por sí solo no prueba la identidad. La confianza y sanitización de este encabezado por parte de un API Gateway no forman parte del código implementado en este módulo.
- No se lee `X-User-Role`. No están implementadas las políticas de autorización de `CLIENTE`, `ORGANIZADOR` y `ADMIN`.
- Este módulo no verifica que un organizador sea titular del evento, no tiene una ruta de listado global para administradores ni muestra autenticación de servicios internos.
- El README indica que las rutas internas no deben exponerse mediante el Gateway; su configuración no está en este módulo y esa condición de despliegue no está verificada aquí.

## Verification Evidence
- `EntradaServiceImplTest` prueba metadatos de página y orden descendente, y verifica el camino de acceso denegado del servicio.
- En el árbol de tests actual no existe un test de controller ni de integración HTTP.
