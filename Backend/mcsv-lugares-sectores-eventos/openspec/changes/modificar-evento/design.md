# Diseño: modificación de eventos

## Context

Ver la motivación en la propuesta y el contrato en la especificación delta. `Evento` tiene `fechaHora`, `lugarId`, una copia de `ciudad`, sectores embebidos y `@Version Long`; no tiene fecha de fin. Spring Data inicia la versión de un evento nuevo en 0. La intención `cancelacionPendiente` es interna y la cancelación la escribe sobre el mismo documento. La búsqueda tiene caché local; las consultas internas no están cacheadas.

## Goals / Non-Goals

**Goals:** mantener el cambio local y el aviso atómicos, proteger frente a escrituras concurrentes, y preservar un payload completo y versionado incluso cuando los avisos se entregan fuera de orden.

**Non-Goals:** crear estados públicos, modificar los contratos de consultas, enviar un aviso para borradores, cambiar la política general de outbox o migrar sectores, precios, reservas y disponibilidad al lugar nuevo.

## Decisions

### Validación y autorización

Agregar una operación específica, sin ampliar los permisos de crear, poner precios o publicar. Reutilizar `UserRoles` para evaluar todos los roles: identidad y roles faltantes dan `401`; `ADMIN` tiene permiso sin titularidad, y `ORGANIZADOR` requiere titularidad; otros casos dan `403`. Un evento inexistente da `404`; cuerpo sin campos, fecha no futura o inválida y lugar inexistente dan `400`. Rechazar `CANCELADO`, `FINALIZADO` y `cancelacionPendiente` con `409`, incluso cuando los valores coinciden. Conservar el formato común de errores.

Resolver el lugar por su ID y usar su nombre y ciudad actuales para el aviso. La respuesta de modificación conserva el formato del evento. El detalle público ya resuelve el lugar mediante `lugarId`; la consulta interna expone fecha y ciudad según su contrato actual, sin agregarle campos. No hay validación de fecha de fin porque el modelo no la tiene.

La comparación de fecha debe respetar la precisión persistida por MongoDB para que un valor representable idéntico no cause una escritura espuria. Los campos ausentes conservan su valor anterior. No escribir ni invalidar caché si no hay cambio real.

### Transacción y concurrencia

Usar un servicio transaccional MongoDB, análogo a `EventoPublicacionOutboxService`: cargar y validar el evento dentro de la transacción, aplicar cambios, guardar con `@Version`, obtener la versión posterior y, si está `PUBLICADO` o `AGOTADO`, insertar el aviso con ID `modificacion-{eventoId}-v{version}`. La versión enviada debe ser un entero no nulo y al menos 1; no usar la versión anterior ni mantener un contador paralelo. Si una escritura o inserción falla, propagar el error y revertir todo.

Verificar las rutas de escritura existentes, incluido el backfill con `MongoTemplate`, para que cada escritura del documento respete el incremento de versión. Las escrituras concurrentes de intención de cancelación y modificación compiten sobre el mismo documento versionado: no se sobrescribe una versión obsoleta; la operación perdedora responde `409` o conserva su comportamiento de error existente. No asumir que una validación previa aislada protege frente a una carrera.

Invocar `CatalogoCacheInvalidador` en la transacción para que actúe después del commit. No invalidar ante rollback. La caché es local: otras instancias y un corte inmediatamente posterior al commit pueden conservar datos anteriores hasta el TTL existente.

### Entrega a Compras

Crear el aviso con el destino `POST {COMPRAS_BASE_URL}/internal/eventos-modificados` y los cinco campos exactos: `eventoId`, `version` numérica, `fechaHora` UTC, `lugar` como nombre y `ciudad`. Guardar el snapshot completo tras la modificación para que la deduplicación de Compras por `eventoId + version` no descarte información si se reciben versiones fuera de orden.

Reutilizar configuración, correlación y dispatcher existentes. `X-Service-Token`, `X-Aviso-Id` y `X-Correlation-Id` acompañan cada entrega; no persistir el token. Todo `2xx` entrega, `400` y `403` son definitivos, y `5xx`, timeout y errores de conexión se reintentan hasta 8 reclamos con backoff de 30 s duplicado y tope de 15 min. Se conservan también las reglas existentes para `408`, `429`, otros `4xx`, `3xx`, lease y token ausente. El HTTP ocurre después del commit; su fallo no revierte el evento ni transforma una modificación confirmada en fallo. El scheduler recupera avisos pendientes.

### Cambio de lugar y sectores

Por decisión de alcance del equipo, conservar sectores, precios y disponibilidad existentes aunque pertenecieran al lugar anterior; no reinicializar ni migrar datos en Reservas. Esto puede dejar sectores de la ubicación anterior asociados a la nueva y afectar la asignación de precios en borradores, que valida pertenencia al lugar. Documentar explícitamente esa limitación; no presentar el cambio como una migración física de asientos.

## Risks / Trade-offs

- [Modificación concurrente con finalización] → El scheduler llama a Reservas antes de confirmar la finalización local; el control optimista por sí solo no deshace una llamada remota ya iniciada. El cambio no introduce coordinación distribuida y debe documentarse la ventana existente.
- [Avisos fuera de orden o respuesta perdida] → Enviar siempre el snapshot completo y mantener ID y versión estables en reintentos; Compras deduplica por evento y versión.
- [Sectores incompatibles con el lugar nuevo] → Conservar los datos por la decisión acordada y documentar que no se migran ni se valida compatibilidad.
- [Rollback o duplicado de aviso] → La inserción y el guardado comparten transacción; no ignorar errores de escritura.
- [Integración entre servicios] → El Gateway solo enruta `GET /eventos/{eventoId}` y el código local de Compras no presenta el receptor de modificaciones. Coordinar habilitación de `PUT` y despliegue del receptor acordado; hasta entonces, el acceso por Gateway o la entrega a Compras no estarán disponibles.

## Migration Plan

Desplegar MS1 con MongoDB configurado para transacciones y el contrato acordado disponible en Compras; conservar el procesamiento de avisos existentes. No se requiere backfill de modificaciones históricas. Ante rollback del código, los avisos ya persistidos deben seguir siendo procesables por el dispatcher compatible, o mantenerse pendientes hasta recuperar una versión compatible; no borrar avisos ni revertir datos automáticamente.
