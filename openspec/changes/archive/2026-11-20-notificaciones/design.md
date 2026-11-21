## Contexto

MS9 recibe avisos de otros servicios, los persiste y envía correos. Debe fallar sin afectar operaciones ya confirmadas. Numeración y puerto quedan a confirmar por el grupo.

## Decisiones

- **Recepción separada del envío:** el POST solo inserta; el envío lo hace un `@Scheduled`. Así la solicitud HTTP nunca espera a SMTP.
- **Idempotencia:** columna `clave_deduplicacion` (acontecimiento + tipo + destinatario) con restricción única. Ante violación, se devuelve la notificación existente.
- **Atomicidad en la selección:** `UPDATE notificacion SET estado='PROCESANDO', vence_procesamiento=? WHERE id=? AND estado='PENDIENTE'`; solo procesa quien obtiene 1 fila afectada.
- **Reintentos:** `intentos`, `proximo_intento`, `ultimo_error`; espera exponencial; máximo configurable (por defecto 5).
- **Recuperación:** segunda tarea que busca PROCESANDO con `vence_procesamiento` vencido.
- **Guardado condicional del resultado:** cada resultado de un envío (ENVIADA, vuelta a PENDIENTE con reintento, ERROR_FINAL por intentos agotados o por error definitivo) se guarda con `UPDATE ... WHERE id=? AND estado='PROCESANDO' AND vence_procesamiento=?`, usando el vencimiento que fijó la toma como identificador de esa toma. Si afecta 0 filas (la recuperación la liberó, o otra instancia la volvió a tomar), no se pisa nada: se registra una advertencia y se sigue.
- **Destinatario:** se guarda solo `usuario_id`; el correo se pide a MS6 al enviar y no se persiste.
- **Plantillas:** una por tipo, texto plano en español, con los datos del aviso.
- **SMTP:** `spring-boot-starter-mail`, configurado por variables de entorno; en desarrollo, un servidor SMTP de pruebas.
- **Resiliencia:** Resilience4j con timeouts de 2 s de conexión y 5 s de lectura como base, y circuit breaker por dependencia.
- **Identidad:** endpoint interno; la autorización de servicio se resuelve con el contrato de identidad de la Entrega 2 (a confirmar con el grupo).

## Modelo de datos

`notificacion(id UUID, clave_deduplicacion UNIQUE, tipo, estado, usuario_id, recurso_id, correlacion_id, version_esquema, ocurrido_en, datos JSON, intentos, proximo_intento, vence_procesamiento, ultimo_error, creada_en, enviada_en)`.

## Contratos

Acordados (del documento de propuesta):
- `POST /internal/notificaciones` (entrada de este servicio).
- `GET /internal/usuarios/{id}/contacto` en MS6 (devuelve el correo).

Contrato a acordar:
- Forma exacta del cuerpo del aviso y lista de campos de `datos` por tipo.
- Códigos de respuesta y de error (propuesta: 202, 400, 403).
- Mecanismo de autorización entre servicios.
- Servidor SMTP y remitente.
- Puerto de Gestión de usuarios y autenticación (MS6): se usa 8087 por defecto (`USUARIOS_URL`). La llamada es directa, no pasa por el API Gateway (8086).
- Motor de base de datos y puerto.

## Riesgos

- Duplicado si se pierde la confirmación de SMTP (aceptado y documentado).
- Si MS6 cae, las notificaciones se acumulan; los reintentos las recuperan cuando vuelva.
