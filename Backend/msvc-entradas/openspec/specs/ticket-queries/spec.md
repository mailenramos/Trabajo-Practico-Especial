# Especificación de consultas y API de entradas

## Purpose
Registrar los endpoints de consulta de entradas, los permisos por rol, la consulta interna por compra y la protección de las rutas internas.

## Status
- **Implementado y probado con tests unitarios del servicio y tests HTTP del controller:** filtros, orden y paginación; permisos de `CLIENTE`, `ADMIN` y `PERSONAL_ACCESO`; lectura de `X-User-Roles` y respaldo `X-User-Role`; `X-Service-Token`; `X-Correlation-Id`; consulta por compra.
- **No establecido:** comprobación de titularidad de eventos por organizadores (no hay consulta de entradas para `ORGANIZADOR`).

## Requirements

### Requirement: Consultar entradas según el rol (US-5.7)
`GET /entradas` y `GET /entradas/{id}` MUST tomar el usuario de `X-User-Id` y sus roles de `X-User-Roles` (o de `X-User-Role` si el primero falta). Un `CLIENTE` MUST ver solo sus entradas y cualquier `idCliente` enviado MUST ignorarse. Un `ADMIN` MUST poder ver las de cualquier cliente y filtrar por `idCliente`. Un usuario sin `CLIENTE` ni `ADMIN` MUST recibir `403 ACCESO_DENEGADO`. Con varios roles MUST aplicarse el conjunto, sin elegir uno.

#### Scenario: Un cliente solicita la entrada de otro cliente
- **WHEN** la entrada solicitada pertenece a otro cliente y el usuario no es `ADMIN`
- **THEN** responde `403 ACCESO_DENEGADO`

#### Scenario: Personal de acceso consulta entradas
- **WHEN** un usuario con solo `PERSONAL_ACCESO` lista o consulta entradas
- **THEN** responde `403 ACCESO_DENEGADO`

#### Scenario: Falta el encabezado de identidad
- **WHEN** se llama a una ruta pública sin `X-User-Id`
- **THEN** responde `401 FALTA_IDENTIFICACION`

### Requirement: Consultar las entradas de una compra (US-5.6)
`GET /internal/compras/{idCompra}/entradas` MUST devolver `{idCompra, entradas}` con el estado actual de cada entrada y MUST NOT emitir ni modificar nada. Una lista vacía MUST significar que no se emitió ninguna entrada.

#### Scenario: Compra sin entradas
- **WHEN** se consulta una compra que no tiene entradas emitidas
- **THEN** responde `200` con `entradas: []`

#### Scenario: Consulta repetida
- **WHEN** se repite la consulta
- **THEN** devuelve el estado actual sin modificar datos

### Requirement: Proteger las rutas internas (US-5.8)
Si `SERVICIO_TOKEN` está configurado, toda ruta `/internal/**` MUST exigir `X-Service-Token` con ese valor (comparación en tiempo constante) y, si falta o no coincide, MUST responder `401 SERVICIO_NO_AUTENTICADO` sin ejecutar la operación. El token MUST NOT escribirse en el log. Las rutas públicas MUST NOT exigirlo. Si `SERVICIO_TOKEN` está vacío, la validación queda desactivada y se avisa al arrancar.

#### Scenario: Pedido interno sin credencial
- **WHEN** se invoca una ruta interna sin `X-Service-Token` o con uno incorrecto
- **THEN** responde `401 SERVICIO_NO_AUTENTICADO` y no se ejecuta la operación

### Requirement: Propagar el identificador de correlación
Cada pedido MUST aceptar `X-Correlation-Id` si es un UUID o generar uno, devolverlo en la respuesta, incluirlo en el MDC de los logs y reenviarlo en las llamadas a otros servicios.

#### Scenario: Correlación inválida
- **WHEN** llega un `X-Correlation-Id` que no es un UUID
- **THEN** se genera uno nuevo y se devuelve en la respuesta

## Implemented Route Inventory
| Método | Ruta | Comportamiento |
|---|---|---|
| GET | `/entradas` | Listado paginado según rol; `estado`, `idEvento` e `idCliente` (solo ADMIN) opcionales |
| GET | `/entradas/{id}` | Detalle para el titular o un ADMIN |
| POST | `/eventos/{idEvento}/ingresos` | Validación de QR (ver `ticket-access`) |
| POST | `/internal/entradas/emision` | Emite entradas; 201 nueva, 200 reintento idempotente, 409 `EVENTO_CERRADO` |
| GET | `/internal/entradas/{id}` | Detalle interno |
| GET | `/internal/compras/{idCompra}/entradas` | Entradas de una compra |
| POST | `/internal/entradas/{id}/reintegro` | `VIGENTE` → `EN_REINTEGRO`; 409 `ENTRADA_UTILIZADA` |
| POST | `/internal/entradas/{id}/devolucion` | `EN_REINTEGRO` → `DEVUELTA` |
| POST | `/internal/eventos/{idEvento}/entradas/reintegro` | Cierre del evento (ver `ticket-lifecycle`) |

## Verification Evidence
- `EntradaServiceImplTest`: paginación, permisos por rol y consulta por compra.
- `EntradaControllerTest`: identidad, roles, `X-Service-Token`, `X-Correlation-Id` y mapeo de errores.
