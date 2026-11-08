# Contrato para el API Gateway

La tabla contiene todas las rutas públicas actuales de `GestionLugaresFisicos`. El Gateway debe exponer cada una (marcada **Sí**) y reenviar el path backend sin cambios al servicio. En local, el backend escucha en `http://localhost:8081` por defecto; `PORT` puede sobrescribirlo. El prefijo público del Gateway no está definido en este repositorio.

Para las rutas públicas, el Gateway obtiene la identidad del usuario autenticado y propaga `X-User-Id` y `X-User-Role`. El microservicio aplica la autorización final. Los roles se comparan sin distinguir mayúsculas y quitando espacios externos; los valores canónicos son `administrador` y `organizador`.

| Método | Path backend | Se publica por Gateway | Headers requeridos | Body | Respuestas esperadas |
|---|---|---:|---|---|---|
| `POST` | `/lugares` | Sí | `X-User-Id`; `X-User-Role: administrador` | Datos del lugar | `201`, `400`, `401`, `403`, `409`, `500` |
| `GET` | `/lugares` | Sí | `X-User-Id`; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403`, `500` |
| `GET` | `/lugares/{id}` | Sí | `X-User-Id`; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403`, `404`, `500` |
| `GET` | `/lugares/{id}/sectores` | Sí | `X-User-Id`; rol `organizador` o `administrador` | Ninguno | `200`, `401`, `403`, `404`, `500` |
| `POST` | `/lugares/{id}/sectores` | Sí | `X-User-Id`; `X-User-Role: administrador` | Datos del sector | `201`, `400`, `401`, `403`, `404`, `409`, `500` |
| `POST` | `/eventos` | Sí | `X-User-Id`; `X-User-Role: organizador` | Datos del evento en borrador | `201`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/sectores/{sectorId}/precio` | Sí | `X-User-Id`; organizador dueño | `{"precio":15000.00}` | `200`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/limite-entradas-por-cliente` | Sí | `X-User-Id`; organizador dueño | `{"limiteEntradasPorCliente":8}` | `200`, `400`, `401`, `403`, `404`, `409`, `500` |
| `PUT` | `/eventos/{eventoId}/publicar` | Sí | `X-User-Id`; organizador dueño | Ninguno | `200`, `400`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/venta/suspender` | Sí | `X-User-Id`; `X-User-Role: administrador` | Ninguno | `200`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/venta/reactivar` | Sí | `X-User-Id`; `X-User-Role: administrador` | Ninguno | `200`, `401`, `403`, `404`, `409`, `503`, `500` |
| `PUT` | `/eventos/{eventoId}/cancelar` | Sí | `X-User-Id`; `X-User-Role: administrador` | Ninguno | `200`, `401`, `403`, `404`, `409`, `503`, `500` |

Para requests con body, el Gateway conserva `Content-Type: application/json`. Las consultas de lugares aceptan los roles `organizador` y `administrador`; los POST de gestión de lugares/sectores requieren `administrador`. Crear, publicar y modificar precio/límite de eventos requiere el organizador dueño. Suspender, reactivar y cancelar requiere `administrador`. Headers ausentes o vacíos reciben `401`; un rol sin permiso recibe `403`.

Al crear un evento, `limiteEntradasPorCliente` es opcional y vale `4` por defecto. `fechaHora` debe ser futura. Los precios son decimales mayores o iguales a cero y solo se asignan a sectores del lugar del evento mientras está en borrador. La API serializa los estados como `borrador`, `publicado`, `agotado`, `cancelado` y `finalizado`.

## Rutas internas excluidas del Gateway

`PUT /eventos/{eventoId}/disponibilidad` es un callback interno que **no se publica** por el Gateway. Reservas lo llama directamente con `{"agotado":true}` o `{"agotado":false}`; no exige `X-User-Id` ni `X-User-Role`. Responde `204` al aplicar el aviso, si es duplicado o si llega tarde para un evento cancelado/finalizado; `404` si no existe el evento y `409` si sigue en borrador.

Tampoco se publican rutas internas bajo `/internal/` ni las llamadas salientes de Eventos a Reservas o Compras. En la Entrega 1, la comunicación entre microservicios es directa y sin autenticación entre servicios. La integración con Compras todavía está pendiente de confirmación y no está implementada.

Los errores controlados usan `timestamp`, `status`, `error`, `mensaje` y, si corresponde, `campos` como lista de `{campo, mensaje}`. OpenAPI está disponible en `/v3/api-docs`; Swagger UI, en `/swagger-ui/index.html`.
