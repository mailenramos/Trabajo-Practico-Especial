# Microservicio 5 – Gestión de Entradas

## 1. Descripción general

El microservicio de Gestión de Entradas forma parte de TicketYa, una plataforma de venta de entradas para eventos.

Su responsabilidad principal es emitir y administrar las entradas una vez que una compra fue confirmada por el microservicio de Compras.

También permite consultar entradas, gestionar reintegros y registrar devoluciones.

## 2. Tecnologías utilizadas

- Java.
- Spring Boot.
- Spring Data JPA.
- PostgreSQL 16.
- Flyway para el control de versiones y migraciones de la base de datos.
- Maven.

## 3. Configuración

| Propiedad | Valor |
|---|---|
| Nombre | `msvc-entradas` |
| Puerto | `8085` |
| Base de datos | PostgreSQL |
| Base de datos (nombre) | `entradas` |
| Gestión de migraciones | Flyway |
| Estrategia de Hibernate | `validate` |

La aplicación utiliza Flyway para mantener actualizada la estructura de la base de datos y Hibernate para validar que las entidades coincidan con el esquema existente.

## 4. Endpoints

### 4.1. Endpoints públicos

Son los endpoints destinados a las consultas realizadas por los usuarios a través del API Gateway.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/entradas` | Lista las entradas del usuario autenticado. |
| GET | `/entradas/{id}` | Consulta el detalle de una entrada propia. |

**Headers utilizados:**

- `X-User-Id`: identifica al usuario autenticado.

La validación de roles y permisos se encuentra pendiente de implementación.

### 4.2. Endpoints internos

Son utilizados para la comunicación entre microservicios.

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/internal/entradas/emision` | Emite las entradas correspondientes a una compra confirmada. |
| GET | `/internal/entradas/{id}` | Consulta una entrada por su identificador. |
| POST | `/internal/entradas/{id}/reintegro` | Marca una entrada para reintegro. |
| POST | `/internal/entradas/{id}/devolucion` | Marca una entrada como devuelta. |
| POST | `/internal/eventos/{idEvento}/entradas/reintegro` | Marca las entradas vigentes de un evento en reintegro por cancelación. |

Estos endpoints no deben exponerse como rutas públicas del Gateway.

## 5. Emisión de entradas

La emisión se realiza cuando el microservicio de Compras informa que una compra fue confirmada.

**Endpoint:**

`POST /internal/entradas/emision`

La petición incluye:

- `idCompra`: identificador único de la compra.
- `idCliente`: identificador del cliente.
- `evento`: información resumida del evento.
- `lugares`: listado de lugares adquiridos.

Cada lugar contiene:

- `idSector`: identificador del sector.
- `nombreSector`: nombre del sector.
- `fila`: número de fila, cuando corresponde.
- `numero`: número de asiento, cuando corresponde.

Los identificadores de eventos y sectores son cadenas de texto porque pertenecen al modelo de datos del microservicio de Eventos.

### Ejemplo de petición

```json
{
  "idCompra": "a1234567-1234-4234-8234-123456789001",
  "idCliente": "cliente-prueba-01",
  "evento": {
    "id": "evento-prueba-01",
    "nombre": "Recital de prueba",
    "comienzaEn": "2026-12-15T21:00:00"
  },
  "lugares": [
    {
      "idSector": "sector-prueba-01",
      "nombreSector": "Platea",
      "fila": 1,
      "numero": 6
    },
    {
      "idSector": "sector-prueba-01",
      "nombreSector": "Platea",
      "fila": 1,
      "numero": 7
    }
  ]
}
```

### Idempotencia

La emisión es idempotente mediante `idCompra`.

Esto significa que si el microservicio recibe varias veces la misma solicitud de emisión para una compra, no debe generar entradas duplicadas. Debe recuperar y devolver la emisión existente.

Además, se controlan los conflictos por lugares que ya tienen una entrada vigente.

## 6. Estados de las entradas

Las entradas manejan los siguientes estados:

| Estado | Descripción |
|---|---|
| `VIGENTE` | Entrada emitida y válida. |
| `EN_REINTEGRO` | Entrada cuyo importe se encuentra en proceso de reintegro. |
| `DEVUELTA` | Entrada cuya devolución fue registrada. |

Las transiciones de estado se realizan mediante las operaciones internas correspondientes.

### Cancelación de eventos

Cuando se cancela un evento, el microservicio recibe una solicitud para marcar sus entradas vigentes en estado `EN_REINTEGRO`.

Las entradas que ya fueron devueltas no deben volver a modificarse.

## 7. Manejo de errores

Los errores se centralizan mediante `GlobalExceptionHandler`.

Todas las respuestas siguen el formato acordado por el grupo:

```json
{
  "timestamp": "2026-11-09T18:30:00",
  "status": 404,
  "error": "ENTRADA_NO_ENCONTRADA",
  "mensaje": "No existe la entrada indicada"
}
```

Los campos utilizados son:

| Campo | Descripción |
|---|---|
| `timestamp` | Fecha y hora del error. |
| `status` | Código de estado HTTP. |
| `error` | Código de negocio del error. |
| `mensaje` | Descripción del error. |
| `campos` | Detalles adicionales de validación, cuando corresponda. |

No se utiliza un campo separado llamado `codigo`.

### Algunos errores contemplados

| Código | HTTP |
|---|---:|
| `VALIDACION` | 400 |
| `ENTRADA_NO_ENCONTRADA` | 404 |
| `TRANSICION_INVALIDA` | 409 |
| `ENTRADA_VIGENTE_DUPLICADA` | 409 |

## 8. Seguridad y autorización

La arquitectura contempla el uso de un API Gateway basado en Spring Cloud Gateway.

Los headers de identidad acordados por el grupo son:

- `X-User-Id`: identificador del usuario autenticado.
- `X-User-Role`: rol del usuario.

Los roles definidos son:

- `CLIENTE`
- `ORGANIZADOR`
- `ADMIN`

Criterios de acceso propuestos:

- CLIENTE: consultar sus propias entradas.
- ORGANIZADOR: consultar las entradas de sus propios eventos.
- ADMIN: consultar todas las entradas.

**Pendiente:** implementar la validación de roles y la comprobación de propiedad de los eventos. También debe definirse la protección de los endpoints internos mediante comunicación autorizada entre microservicios.

## 9. Pruebas realizadas

Se realizaron pruebas utilizando Postman y la base de datos PostgreSQL.

| Operación | Resultado |
|---|---|
| Emisión de entradas | Correcto – HTTP 201 |
| Repetición de una emisión | Se recuperan las mismas entradas. |
| Consulta de entrada individual | Correcto |
| Listado de entradas del cliente | Correcto |
| Marcar entrada en reintegro | Correcto |
| Marcar entrada como devuelta | Correcto |
| Reintegro por cancelación de evento | Correcto |
| Consulta de entrada inexistente | Correcto – HTTP 404 |

## 10. Integración con otros microservicios

El microservicio de Entradas se comunica principalmente con:

- **Microservicio de Compras:** recibe las solicitudes de emisión luego de confirmar una compra.
- **Microservicio de Eventos:** utiliza información resumida de eventos y sectores. También deberá consultarse para verificar la propiedad de los eventos cuando se implementen los permisos del organizador.

El servicio funciona en el puerto `8085` y no utiliza `context-path` ni reescritura de rutas.