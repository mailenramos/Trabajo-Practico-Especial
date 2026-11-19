# Propuesta: política de fila del evento

## Motivo

MS7 y los consumidores de reportes necesitan consultar una fuente autoritativa del requisito de fila y de los datos actuales del evento. Hoy MS1 no almacena `requiereFila` y la consulta interna expone un conjunto reducido de datos para Compras.

## Cambio

- Agregar a `Evento` el indicador booleano `requiereFila`, con valor inicial `false`; los documentos antiguos que no lo contienen se leen como `false`, sin migración.
- Ampliar la consulta interna existente con el indicador, organizador y datos de evento y sectores ya almacenados, conservando el contrato existente para Compras.
- Permitir a MS7 activar o desactivar el indicador por `PUT /internal/eventos/{eventoId}/politica-fila`, con actualización idempotente y sin cambiar la versión cuando el valor ya coincide. En eventos cancelados solo se rechaza la activación; la desactivación está permitida.
- Evaluar primero la existencia del evento (`404`), luego la validez del body (`400`) y finalmente rechazar la activación de un evento cancelado (`409`).
- Mantener las rutas internas sin headers de usuario, fuera del Gateway y sin agregar autenticación entrante por token de servicio.
- Documentar el contrato y preparar requests Postman para la consulta y la política.

## Alcance

La pausa de fila y la lógica de reservas permanecen a cargo de sus servicios: una pausa no cambia el indicador y los eventos sin fila siguen el proceso habitual. No se agrega caché, migración de datos, endpoints para pausar ni validación entrante de `X-Service-Token`.

## Capacidades afectadas

- `eventos`: ampliar la consulta interna existente y especificar la política de fila.

## Impacto

Modelo MongoDB de eventos, consulta y controlador internos, pruebas, README y colección Postman. El campo ya existente `fechaInicio` se conserva en la respuesta para no romper a Compras.
