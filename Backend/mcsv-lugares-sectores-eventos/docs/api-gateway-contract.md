# Contrato para el API Gateway

La tabla contiene todas las rutas públicas actuales de `mcsv-lugares-sectores-eventos`. El Gateway debe exponer cada una (marcada **Sí**) y reenviar el path backend sin cambios al servicio. En local, el backend escucha en `http://localhost:8081` por defecto; `PORT` puede sobrescribirlo. El prefijo público del Gateway no está definido en este repositorio.

En operaciones protegidas, el cliente envía `Authorization: Bearer <jwt>` al Gateway, no los headers de identidad. El Gateway valida el JWT e inyecta `X-User-Id` con el valor de `sub` y `X-User-Roles` con los roles separados por comas; MS1 no valida JWT. El microservicio aplica la autorización final y evalúa todos los roles de la lista, sin precedencia arbitraria; acepta `CLIENTE`, `ORGANIZADOR` y `ADMIN`, ignora diferencias de mayúsculas y recorta espacios.

**Nota:** `GET /eventos` y `GET /eventos/{eventoId}` para eventos que no son borradores son públicos también a través del Gateway.

| Método | Path backend | Se publica por Gateway | Headers requeridos | Body | Respuestas esperadas |
|---|---|---:|---|---|---|
| `POST` | `/lugares` | Sí | `X-User-Id`; `X-User-Roles` incluye `ADMIN` | Datos del lugar | `201`, `400`, `401`, `403`, `409`, `500` |
| `GET` | `/lugares` | Sí | `X-User-Id`; roles incluyen `ORGANIZADOR` o `ADMIN` | Ninguno | `200`, `401`, `403`, `500` |
| `GET` | `/lugares/{id}` | Sí | `X-User-Id`; roles incluyen `ORGANIZADOR` o `ADMIN` | Ninguno | `200`, `401`, `403`, `404`, `500` |
| `GET` | `/lugares/{id}/sectores` | Sí | `X-User-Id`; roles incluyen `ORGANIZADOR` o `ADMIN` | Ninguno | `200`, `401`, `403`, `404`, `500` |
| `POST` | `/lugares/{id}/sectores` | Sí | `X-User-Id`; `X-User-Roles` incluye `ADMIN` | Datos del sector | `201`, `400`, `401`, `403`, `404`, `409`, `500` |
| `POST` | `/eventos` | Sí | `X-User-Id`; `X-User-Roles` incluye `ORGANIZADOR` | Datos del evento en borrador | `201`, `400`, `401`, `403`, `404`, `409`, `500` |
| `GET` | `/eventos` | Sí | Público | `tipo`, `organizadorId`, `participante`, `ciudad`, `fechaDesde`, `fechaHasta`, `orden`, `direccion`, `q`, `page`, `size` | `200`, `400`, `500` |
| `GET` | `/eventos/{eventoId}` | Sí | Público salvo borradores: `X-User-Id` y `X-User-Roles` | Ninguno | `200`, `401` (borrador sin identidad), `404` (inexistente, oculto o borrador ajeno), `500` |
| `PUT` | `/eventos/{eventoId}` | Sí | JWT validado por Gateway; `X-User-Id` y `X-User-Roles` con `ORGANIZADOR` titular o `ADMIN` | `fechaHora` UTC futura y/o `lugarId` existente | `200`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/sectores/{sectorId}/precio` | Sí | `X-User-Id`; `X-User-Roles` incluye `ORGANIZADOR`; titular del evento | `{"precio":15000.00}` | `200`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/limite-entradas-por-cliente` | Sí | `X-User-Id`; `X-User-Roles` incluye `ORGANIZADOR`; titular del evento | `{"limiteEntradasPorCliente":8}` | `200`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/publicar` | Sí | `X-User-Id`; `X-User-Roles` incluye `ORGANIZADOR`; titular del evento | Ninguno | `200`, `400`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/venta/suspender` | Sí | `X-User-Id`; `X-User-Roles` incluye `ADMIN` | Ninguno | `200`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/venta/reactivar` | Sí | `X-User-Id`; `X-User-Roles` incluye `ADMIN` | Ninguno | `200`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/cancelar` | Sí | `X-User-Id`; `X-User-Roles` incluye `ADMIN` | Opcional: `{"motivo":"Cancelación por condiciones climáticas"}` | `200`, `401`, `403`, `404`, `409`, `503`, `500` |

Para requests con body, el Gateway conserva `Content-Type: application/json`. Las consultas de lugares aceptan `ORGANIZADOR` y `ADMIN`; los POST de gestión de lugares/sectores requieren `ADMIN`. Crear, publicar y modificar precio/límite de eventos requiere el organizador titular (`ORGANIZADOR`). Suspender, reactivar y cancelar requiere `ADMIN`. En MS1, la identidad o la lista de roles ausente/vacía recibe `401` en rutas protegidas; una identidad autenticada con rol insuficiente recibe `403`. En el detalle, MS1 devuelve `401` al consultar un borrador sin identidad; un borrador ajeno o sin rol autorizado se oculta con `404`.

El Gateway enruta también `PUT /eventos/{eventoId}`, conservando método y path, y propaga `X-User-Id` y `X-User-Roles`. El `PUT` no altera sectores, precios ni disponibilidad al cambiar lugar y no llama a Reservas.

Al crear un evento, `limiteEntradasPorCliente` es opcional y vale `4` por defecto. `fechaHora` debe ser futura. Los precios son decimales mayores o iguales a cero y solo se asignan a sectores del lugar del evento mientras está en borrador. La API serializa los estados como `borrador`, `publicado`, `agotado`, `cancelado` y `finalizado`.

## Rutas internas excluidas del Gateway

`PUT /eventos/{eventoId}/disponibilidad` es un callback interno que **no se publica** por el Gateway. Reservas lo llama directamente con `{"agotado":true}` o `{"agotado":false}`; no exige `X-User-Id` ni `X-User-Roles`. Responde `204` al aplicar el aviso, si es duplicado o si llega tarde para un evento cancelado/finalizado; `404` si no existe el evento y `409` si sigue en borrador.

`GET /internal/eventos/{eventoId}` es una ruta interna para Compras que **no se publica** por el Gateway y no exige headers de usuario. Devuelve `200` con datos del evento en cualquier estado o `404` si no existe.

`GET /internal/eventos-publicados` es una ruta interna para Preferencias y recomendaciones (MS8) que **no se publica** por el Gateway ni exige headers de usuario. Devuelve una página de eventos `publicado` o `agotado`, ordenada por `fechaHora` e ID; `page` inicia en 0, `size` usa 100 por defecto y admite de 1 a 200. Parámetros inválidos responden `400` en el formato de error estándar.

El Gateway usa una lista explícita de rutas públicas: no debe publicar el callback `PUT /eventos/{eventoId}/disponibilidad`, ninguna ruta bajo `/internal/**` (incluidos `GET /internal/eventos/{eventoId}` y `GET /internal/eventos-publicados`), ni las llamadas salientes de Eventos a Reservas, Compras o Preferencias. Los avisos salientes usan `X-Service-Token`: cancelación a `POST /eventos-cancelados`, modificación a `POST /internal/eventos-modificados` y publicación a `POST /internal/preferencias/eventos-publicados`; ninguno es una ruta de este servicio.

La persistencia de avisos es interna y no agrega rutas al Gateway. MS1 guarda los avisos en la colección MongoDB `outbox`, con `_id_` único y los índices compuestos `idx_outbox_estado_proximo_intento` (`estado`, `proximoIntento`, `intentos`) e `idx_outbox_estado_lease` (`estado`, `leaseHasta`, `intentos`). Los tipos incluyen `CANCELACION` para Compras y `PUBLICACION` para Preferencias, con IDs estables `cancelacion-{eventoId}` y `publicacion-{eventoId}`. El antiguo campo `avisoCancelacionPendiente` de `eventos` fue retirado; un backfill transitorio migra documentos legacy al iniciar.

Los errores controlados usan `timestamp`, `status`, `error`, `mensaje` y, si corresponde, `campos` como lista de `{campo, mensaje}`. OpenAPI está disponible en `/v3/api-docs`; Swagger UI, en `/swagger-ui/index.html`.
