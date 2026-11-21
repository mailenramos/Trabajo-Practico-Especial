## Diseño — Validación QR y control de acceso (Entrega 2)

User stories: `docs/historias-ms5-entrega2.md` (US-5.1 a US-5.9). Contratos con MS1 y MS3: tabla «Contratos acordados» de ese documento.

### Migración V4
- `entradas.estado`: agregar `UTILIZADA` al CHECK.
- Recrear `uk_entrada_lugar_vigente` con `WHERE estado IN ('VIGENTE','UTILIZADA') AND numero IS NOT NULL` (un asiento usado sigue ocupado).
- `control_eventos (id_evento VARCHAR(64) PK, cerrado_el TIMESTAMPTZ NULL, creado_el TIMESTAMPTZ NOT NULL DEFAULT now())`: existe aunque el evento no tenga entradas.
- `ingresos (id UUID PK, id_entrada UUID NOT NULL UNIQUE REFERENCES entradas, id_evento VARCHAR(64) NOT NULL, validado_por VARCHAR(64) NOT NULL, excepcional BOOLEAN NOT NULL, validado_el TIMESTAMPTZ NOT NULL)`.

### Coordinación local por evento
- `insert into control_eventos ... on conflict do nothing` y luego:
  - emisión y validación: `select ... for share` (concurrentes entre sí);
  - cierre: `select ... for update` (espera a las operaciones en curso).
- Emisión: primero la búsqueda idempotente por `idCompra` (una compra ya emitida devuelve sus entradas aunque el evento esté cerrado); si es nueva y el evento está cerrado → `409 EVENTO_CERRADO`.
- Cierre (`POST /internal/eventos/{id}/entradas/reintegro`): marca `cerrado_el` si es nulo, pasa `VIGENTE` → `EN_REINTEGRO` y responde `{idEvento, entradasMarcadas, cerrado: true}`. Idempotente. Las `UTILIZADA` no cambian.

### Validación (`POST /eventos/{eventoId}/ingresos`, `{codigoQr}`)
1. Roles de `X-User-Roles` (respaldo `X-User-Role`): sin `PERSONAL_ACCESO` ni `ADMIN` → `403 ACCESO_DENEGADO`.
2. Sin `ADMIN`: consulta a MS1 fuera de la transacción (timeouts cortos, envía `X-Service-Token` y `X-Correlation-Id`). `false` → `403 PERSONAL_NO_AUTORIZADO`; error o timeout → `503 AUTORIZACION_NO_DISPONIBLE`. Con `ADMIN`: `excepcional = true`, sin consulta.
3. Transacción: bloqueo compartido del evento; cerrado → `409 EVENTO_CERRADO`; QR inexistente → `404 ENTRADA_NO_ENCONTRADA`; otro evento → `409 ENTRADA_DE_OTRO_EVENTO`; `UPDATE ... SET estado='UTILIZADA' WHERE id=? AND estado='VIGENTE'`; 0 filas → `409 ENTRADA_YA_UTILIZADA` (con la fecha del ingreso) o `409 ENTRADA_NO_HABILITADA`; 1 fila → insertar ingreso y responder `201`.

### Otros cambios
- `marcarEnReintegro` sobre `UTILIZADA` → `409 ENTRADA_UTILIZADA`.
- `GET /internal/compras/{idCompra}/entradas` → `{idCompra, entradas: [...]}` (lista vacía = no se emitió).
- Consultas públicas: `CLIENTE` solo las propias; `ADMIN` todas (filtro opcional `idCliente`); solo `PERSONAL_ACCESO` → 403.
- `/internal/**`: si `SERVICIO_TOKEN` está configurado, exige `X-Service-Token` (comparación en tiempo constante) → `401 SERVICIO_NO_AUTENTICADO`; si no, se registra una advertencia al arrancar (decisión del grupo pendiente).
- Filtro de `X-Correlation-Id` (MDC + respuesta). Quitar el `System.out.println` del controller.
- Gateway: ruta `POST /eventos/{eventoId}/ingresos` → MS5, roles `PERSONAL_ACCESO` y `ADMIN`.

### Tests
- Unitarios del servicio actualizados; tests de concurrencia con Testcontainers (requieren Docker): validaciones simultáneas del mismo QR, validación contra bloqueo por devolución, emisión contra cierre.
