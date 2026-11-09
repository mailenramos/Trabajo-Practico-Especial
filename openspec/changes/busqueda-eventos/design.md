# Design

## Context

El servicio persiste eventos en la colección MongoDB `eventos`. Cada documento ya conserva `ciudad`, `estado`, `fechaHora`, `organizadorId`, `participantes`, `precioMinimo` y sectores embebidos con su precio. Los IDs se exponen como String; el ID de evento es el ObjectId en hexadecimal y `sectorId` conserva el UUID String del sector. Los cambios `lugares-y-sectores` y `eventos-ciclo-de-vida` están archivados. Se agregan rutas GET públicas sin colisionar con `GET /internal/eventos/{eventoId}`, que sigue siendo el contrato interno de Compras.

Las solicitudes públicas requieren `X-User-Id` y `X-User-Role`. Para este alcance los roles válidos son `CLIENTE`, `ORGANIZADOR` y `ADMIN`; una identidad incompleta da `401`, y un rol no admitido da `403`. Los errores mantienen el formato común `timestamp/status/error/mensaje/campos`.

## Goals / Non-Goals

**Goals:**
- Buscar eventos visibles con filtros combinables, paginación y orden ejecutados en MongoDB.
- Mostrar un detalle público y aplicar la regla de privacidad de borradores.
- Agregar disponibilidad actual de Reservas cuando el estado del evento lo requiera, con degradación a disponibilidad `null`.

**Non-Goals:**
- Implementar compras, reservas, disponibilidad local o cambios al ciclo de vida.
- Cambiar los contratos internos existentes de Compras o el formato de `GET /internal/eventos/{eventoId}`.
- Implementar autocompletado o un motor de búsqueda externo.

## Decisions

### Endpoints y respuesta de búsqueda

Agregar `GET /eventos` con query parameters. Hoy esa ruta responde `405 Method Not Allowed`; este cambio reemplaza ese comportamiento por la búsqueda paginada con `200 OK` (o `400` para parámetros inválidos):

| Parámetro | Uso |
|---|---|
| `tipo` | Igualdad por tipo de evento. |
| `organizadorId` | Igualdad por organizador. |
| `participante` | Busca un participante del arreglo `participantes`. |
| `ciudad` | Igualdad por ciudad copiada del lugar. |
| `fechaDesde`, `fechaHasta` | Límites inclusivos de `fechaHora`, en ISO-8601 UTC. |
| `orden` | `fecha`, `precio` o `relevancia`; default `fecha`. |
| `direccion` | `asc` o `desc`; solo altera el orden `precio`, default `asc`. Para los demás órdenes no altera el orden definido. |
| `q` | Texto libre opcional para relevancia, sobre nombre, descripción y participantes. |
| `page`, `size` | Página base cero y tamaño; defaults `0` y `20`, máximo `100`. |

Los filtros se combinan con AND y siempre se agrega `estado IN (publicado, agotado)`. Sin filtros se devuelven todos los publicados y agotados. Una página sin coincidencias responde `200` con `contenido: []`; nunca se devuelve borrador, cancelado ni finalizado en la búsqueda.

`fechaDesde` y `fechaHasta` son inclusivos. Un formato de fecha inválido, `fechaDesde > fechaHasta`, un `orden` desconocido, una `direccion` distinta de `asc|desc`, `page < 0`, `size < 1` o `size > 100` devuelve `400` con el error estándar.

Formato de respuesta:

```json
{
  "contenido": [
    {
      "id": "672a1f77bcf86cd799439011",
      "nombre": "Festival Central",
      "tipo": "MUSICA",
      "participantes": ["Artista A"],
      "fechaHora": "2027-05-16T00:00:00Z",
      "ciudad": "Buenos Aires",
      "estado": "publicado",
      "ventaSuspendida": false,
      "precioMinimo": 15000.00
    }
  ],
  "pagina": 0,
  "tamano": 20,
  "totalElementos": 1,
  "totalPaginas": 1
}
```

### Orden y relevancia

- `fecha`: `fechaHora` ascendente, del evento próximo al más lejano. `direccion` no cambia este orden.
- `precio`: `precioMinimo` ascendente por defecto o descendente con `direccion=desc`. Los eventos visibles publicados o agotados deben tener precio mínimo calculado; se excluyen documentos que no lo tengan para este orden.
- `relevancia`: con `q`, usa el puntaje de búsqueda de texto descendente y desempata por `fechaHora` ascendente; sin `q`, equivale a `fecha` ascendente.

El índice de texto de MongoDB cubre `nombre`, `descripcion` y `participantes`. La búsqueda textual usa el puntaje de MongoDB; no se añade un servicio de búsqueda aparte. Las reglas de visibilidad y los demás filtros se aplican también a resultados con `q`.

### Consultas e índices MongoDB

Usar `MongoTemplate`, `Criteria`, `TextCriteria` y `Pageable` para componer criterios dinámicos y ejecutar filtros, sort, skip, limit y conteo en la base. Los filtros adicionales no se cargan para filtrarlos en memoria.

Definir índices de consulta:

- `{ estado: 1, fechaHora: 1 }`
- `{ estado: 1, ciudad: 1, fechaHora: 1 }`
- `{ tipo: 1 }`
- `{ organizadorId: 1 }`
- `{ participantes: 1 }` (multikey)
- Un índice de texto sobre `{ nombre, descripcion, participantes }`.

MongoDB admite un solo índice de texto por colección; ese índice compuesto cubre los tres campos del criterio `q`.

### Detalle público y visibilidad

Agregar `GET /eventos/{eventoId}`. La ruta se distingue de la consulta interna existente `/internal/eventos/{eventoId}`.

Los tres roles admitidos pueden consultar el detalle. Si el evento no existe se devuelve `404`. Si está en `borrador`, solo lo puede consultar el organizador dueño (comparando `X-User-Id` con `organizadorId`) o un administrador; para los demás se devuelve `404` para no revelar su existencia. Los eventos `publicado`, `agotado`, `cancelado` y `finalizado` son visibles a los tres roles. La respuesta incluye el identificador del organizador como `organizadorId`.

El detalle incluye los datos del evento, el lugar (resuelto localmente por `lugarId`) y los sectores embebidos con nombre, tipo, configuración, precio y campo `disponibilidad`. Para un evento distinto de `publicado` o `agotado`, `disponibilidad` es `null` y no se consulta a Reservas.

Ejemplo representativo del detalle:

```json
{
  "id": "672a1f77bcf86cd799439011",
  "nombre": "Festival Central",
  "descripcion": "Música en vivo",
  "tipo": "MUSICA",
  "organizadorId": "organizador-42",
  "participantes": ["Artista A"],
  "fechaHora": "2027-05-16T00:00:00Z",
  "estado": "publicado",
  "ventaSuspendida": false,
  "lugar": {"id":"507f1f77bcf86cd799439011","nombre":"Estadio Central","direccion":"Av. Central 100","ciudad":"Buenos Aires"},
  "sectores": [
    {
      "sectorId": "550e8400-e29b-41d4-a716-446655440000",
      "nombre": "Platea",
      "tipo": "NUMERADO",
      "filas": 1,
      "asientosPorFila": 2,
      "capacidad": null,
      "precio": 15000.00,
      "disponibilidad": {"capacidadTotal":2,"cantidadDisponible":1,"asientosDisponibles":[{"fila":1,"numero":2}]}
    }
  ]
}
```

### Disponibilidad en el detalle

Extender la interfaz existente `ReservasClient` con la consulta `GET /disponibilidad/eventos/{eventoId}` y mantener el adaptador HTTP detrás de la misma interfaz para poder sustituirlo por un cliente simulado en pruebas. La URL base y los timeouts existentes `reservas.base-url`, `reservas.connect-timeout` y `reservas.read-timeout` se reutilizan.

Reservas devuelve `200` con `eventoId` y sectores que contienen `sectorId`, `tipo`, `capacidadTotal`, `cantidadDisponible` y, para sectores numerados, `asientosDisponibles` con `{fila, numero}` enteros. Se combina cada resultado con el sector/precio local por `sectorId`, nunca por posición de lista. Respuesta representativa:

```json
{
  "sectorId": "550e8400-e29b-41d4-a716-446655440000",
  "nombre": "Platea",
  "tipo": "NUMERADO",
  "precio": 15000.00,
  "disponibilidad": {
    "capacidadTotal": 2,
    "cantidadDisponible": 1,
    "asientosDisponibles": [{"fila": 1, "numero": 2}]
  }
}
```

Para sectores no numerados `asientosDisponibles` puede omitirse; `cantidadDisponible` informa cuántas entradas quedan. Si Reservas responde `404` o `500`, falla o vence el timeout, se devuelve de todos modos el detalle y cada sector lleva `disponibilidad: null`. No se propaga un `503` por este fallo opcional. Los errores se registran para observabilidad.

## Risks / Trade-offs

- [Los índices compuestos no aceleran todas las combinaciones posibles] → medir consultas del catálogo y añadir índices solo con evidencia de necesidad.
- [La búsqueda de texto de MongoDB es simple y no ofrece relevancia lingüística avanzada] → el contrato limita relevancia a puntaje de texto y fecha.
- [Reservas puede no responder y la disponibilidad del detalle quedar temporalmente desconocida] → mantener el resto del detalle y usar `disponibilidad: null`.
- [Un borrador no debe filtrarse] → aplicar la regla de visibilidad antes de construir respuesta o consultar Reservas.

## Migration Plan

No requiere migración de documentos: los datos consultados ya están en `eventos`, lugares y sectores locales. Crear los índices requeridos, publicar las dos rutas GET públicas en el Gateway con propagación de identidad, y mantener `GET /internal/eventos/{eventoId}` fuera del Gateway. La integración con disponibilidad amplía `ReservasClient` sin cambiar los contratos existentes.
