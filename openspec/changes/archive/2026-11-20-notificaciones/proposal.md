## Por qué

La Entrega 2 incorpora el microservicio **Gestión de notificaciones** (MS9, puerto 8090 a confirmar por el grupo). Hoy ningún servicio avisa por correo al cliente cuando se confirma una compra, se resuelve una devolución, se cancela o modifica un evento o se publica un evento de su interés. El envío no puede bloquear ni revertir la operación principal, por lo que se resuelve de forma asíncrona con registro persistente y reintentos.

## Qué cambia

- Nuevo microservicio con base de datos propia (motor a confirmar por el grupo; se asume MySQL, igual que MS3).
- Endpoint interno `POST /internal/notificaciones` que recibe acontecimientos, los guarda de forma idempotente y confirma la recepción sin enviar el correo en la misma solicitud.
- Tarea programada (Spring Scheduler) que toma notificaciones pendientes, obtiene el correo del destinatario en Gestión de usuarios y envía por SMTP.
- Estados PENDIENTE, PROCESANDO, ENVIADA y ERROR_FINAL, con cantidad de intentos, próximo intento y último error.
- Recuperación de notificaciones que quedaron en PROCESANDO tras una caída.
- Plantillas de contenido por tipo de notificación.
- Circuit breaker y timeouts (Resilience4j) hacia Gestión de usuarios y SMTP.

## Fuera de alcance

- Broker de mensajes (se reutiliza el patrón outbox + HTTP de los demás servicios).
- Endpoints públicos para consultar notificaciones.
- Garantía de entrega exactamente una vez: si SMTP acepta el correo y se pierde la confirmación, un reintento puede duplicarlo.
- Otros canales (SMS, push) y preferencias de opt-out.
- Que el productor de cada acontecimiento lo emita: cada servicio modifica su propio outbox (MS1, MS3, MS8) y no es parte de este cambio.
- Tests de carga y Docker Compose global (los hace el grupo).

## Servicios externos de los que depende

- Gestión de usuarios y autenticación (MS6): `GET /internal/usuarios/{id}/contacto` para obtener el correo.
- Servidor SMTP o proveedor de correo (a definir; para desarrollo, un servidor SMTP de pruebas).
- Productores de acontecimientos: Compras y devoluciones (MS3), Lugares, sectores y eventos (MS1) vía MS3, y Preferencias y recomendaciones (MS8).

## Impacto

Código nuevo en `Backend/mcsv-notificaciones`. Los productores necesitarán un worker de outbox que llame al endpoint; su implementación queda en cada servicio.
