# Evidencia de compra con fila virtual activa

Fecha: 23 de noviembre de 2026, aproximadamente 19:22 ART. Se usaron los contenedores locales ya ejecutados de MS1, MS2, MS3, MS4, MS5, MS6, MS7 y API Gateway, con sus bases reales. Las solicitudes de usuario se enviaron a `http://127.0.0.1:8086`, con JWT obtenido del MS6 real; el pago fue simulado por MS4. No se reiniciaron ni eliminaron contenedores o volúmenes.

Se crearon un lugar, un sector no numerado de capacidad 10 y un evento de prueba, que permanecen en el entorno local. El organizador publicó el evento y activó la fila desde el Gateway.

| Paso | Resultado observado |
| --- | --- |
| Login de administrador, organizador y cliente | HTTP 200 para cada uno |
| Alta de lugar, sector y evento | HTTP 201 para cada uno |
| Precio y publicación del evento | HTTP 200 para cada uno |
| Activación de la fila por el organizador | HTTP 204 |
| Reserva del cliente antes de ingresar a la fila | HTTP 409; no se creó reserva |
| Ingreso a la fila | HTTP 201; turno `HABILITADO` |
| Reserva con turno vigente | HTTP 201; reserva `VIGENTE` |
| Consulta del turno tras reservar | HTTP 200; turno `CONSUMIDO` |
| Nueva reserva con el turno ya consumido | HTTP 409 |
| Creación de compra | HTTP 201; compra `PENDIENTE` |
| Pago simulado | HTTP 200; compra `CONFIRMADA` |
| Consulta posterior de la compra | HTTP 200; compra `CONFIRMADA` |

Identificadores de los datos creados: evento `6b04bcae3288ce2e952c1e75`, sector `da49eae5-2d3d-46bb-9a23-1c1a09574ac8`, turno `1`, reserva `72a3f35f-04d6-40b3-b4ec-99be0dcc30e1`, compra `46837a09-fce2-4717-8fd0-8fcd2590282b`. Se usaron claves de solicitud e idempotencia distintas para las operaciones de prueba; no se guardaron JWT ni contraseñas en esta evidencia.

Esta ejecución acredita el camino feliz y el rechazo sin turno válido para un cliente, con los servicios indicados. No prueba cancelaciones, fallos remotos, vencimiento de turno, notificaciones, concurrencia ni el arranque completo del Compose general. La [evidencia de concurrencia de MS2](evidencia-concurrencia-ms2.md) y el [informe JMeter sin fila](../../../docs/pruebas-carga/informe.md) corresponden a ejecuciones separadas.
