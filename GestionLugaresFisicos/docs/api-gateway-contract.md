# Contrato para el API Gateway

Este servicio publica las rutas backend de la tabla. El Gateway debe enrutar las rutas públicas correspondientes a estos paths conservándolos al reenviar la solicitud. El prefijo público del Gateway no está definido en este repositorio.

El Gateway debe propagar `X-User-Id` y `X-User-Role` en todas las solicitudes y obtenerlos de la identidad autenticada del usuario. El microservicio aplica la autorización final.

| Método | Path backend | Headers | Body de solicitud | Respuestas esperadas |
|---|---|---|---|---|
| `POST` | `/lugares` | `X-User-Id` no vacío; `X-User-Role: administrador` | `CreateLugarRequest` (ejemplo debajo) | `201`, `400`, `401`, `403`, `409` |
| `GET` | `/lugares` | `X-User-Id` no vacío; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403` |
| `GET` | `/lugares/{id}` | `X-User-Id` no vacío; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403`, `404` |
| `GET` | `/lugares/{id}/sectores` | `X-User-Id` no vacío; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403`, `404` |
| `POST` | `/lugares/{id}/sectores` | `X-User-Id` no vacío; `X-User-Role: administrador` | `CreateSectorRequest` (uno de los ejemplos debajo) | `201`, `400`, `401`, `403`, `404`, `409` |
| `POST` | `/eventos` | `X-User-Id` no vacío; `X-User-Role: organizador` | `CreateEventoRequest` | `201`, `400`, `401`, `403`, `404` |
| `PUT` | `/eventos/{eventoId}/sectores/{sectorId}/precio` | `X-User-Id` no vacío; `X-User-Role: organizador` | `{"precio":15000.00}` | `200`, `400`, `401`, `403`, `404`, `409` |
| `PUT` | `/eventos/{eventoId}/limite-entradas-por-cliente` | `X-User-Id` no vacío; `X-User-Role: organizador` | `{"limiteEntradasPorCliente":8}` | `200`, `400`, `401`, `403`, `404`, `409` |

Los valores de rol se comparan sin distinguir mayúsculas y minúsculas después de quitar espacios externos. Por ejemplo, ` Organizador ` equivale a `organizador`. Los roles `cliente` u otros roles reciben `403` en las consultas; los POST de gestión aceptan únicamente administrador. Headers ausentes o vacíos reciben `401`.

Los eventos se crean con estado `borrador`, asociando el usuario de `X-User-Id` como organizador. El límite por cliente es opcional al crear y vale 4 si se omite; solo se cambia durante el borrador. La fecha/hora de inicio debe ser futura. Los precios son decimales mayores o iguales a cero y solo se asignan a sectores del lugar del evento mientras está en borrador.

## Ejemplos de solicitud

### Registrar lugar

```http
POST /lugares
X-User-Id: admin-1
X-User-Role: administrador
Content-Type: application/json
```

```json
{
  "nombre": "Teatro Central",
  "direccion": "Av. Corrientes 1234",
  "ciudad": "Buenos Aires",
  "latitud": -34.6037,
  "longitud": -58.3816
}
```

### Agregar sector numerado

```http
POST /lugares/507f1f77bcf86cd799439011/sectores
X-User-Id: admin-1
X-User-Role: administrador
Content-Type: application/json
```

```json
{
  "nombre": "Platea",
  "tipo": "NUMERADO",
  "filas": 20,
  "asientosPorFila": 30
}
```

### Agregar sector no numerado

```json
{
  "nombre": "Campo",
  "tipo": "NO_NUMERADO",
  "capacidad": 500
}
```

### Crear evento en borrador

```http
POST /eventos
X-User-Id: organizador-1
X-User-Role: organizador
Content-Type: application/json
```

```json
{
  "nombre": "Festival Central",
  "descripcion": "Música en vivo",
  "tipo": "MUSICA",
  "participantes": ["Artista A", "Artista B"],
  "fechaHora": "2027-05-16T00:00:00Z",
  "lugarId": "507f1f77bcf86cd799439011"
}
```

### Definir precio y límite durante el borrador

```http
PUT /eventos/672a1f77bcf86cd799439011/sectores/550e8400-e29b-41d4-a716-446655440000/precio
X-User-Id: organizador-1
X-User-Role: organizador
Content-Type: application/json
```

```json
{"precio":15000.00}
```

Para actualizar el límite, `PUT /eventos/{eventoId}/limite-entradas-por-cliente` recibe `{"limiteEntradasPorCliente":8}`. Si se omite el campo al crear el evento, se usa 4.

## Respuestas y errores

- `200 OK`: consulta correcta; las listas pueden estar vacías.
- `201 Created`: lugar o sector creado; la respuesta JSON contiene el recurso y su ID.
- `400 Bad Request`: cuerpo inválido, campos obligatorios vacíos, coordenadas fuera de rango o configuración incompatible con el tipo de sector.
- `401 Unauthorized`: falta `X-User-Id` o `X-User-Role`, o su valor está vacío.
- `403 Forbidden`: el rol no tiene permiso para la operación solicitada.
- `404 Not Found`: el ID del lugar no existe.
- `409 Conflict`: conflicto de clave o de actualización concurrente.

Todos los errores controlados usan este formato común; `campos` aparece para errores de validación y se omite si no aplica:

```json
{
  "timestamp": "2026-11-07T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "mensaje": "No existe el lugar 507f1f77bcf86cd799439011"
}
```

Las rutas de integración con Reservas y Compras no se incluyen en el Gateway. Reservas y Compras se documentan por separado en `openspec/changes/eventos-ciclo-de-vida/design.md`.

La especificación OpenAPI completa, con schemas, ejemplos y respuestas por endpoint, está disponible en `/v3/api-docs`; Swagger UI está en `/swagger-ui/index.html`.
