## Context

Registro retrospectivo: la implementación antecede a estos artefactos. Fuentes normativas: consigna, propuesta general sección 1.2 y 2.2, historias originales y `documentacion/User-Stories-Entrega-2.md`; la matriz de cumplimiento, OpenAPI, código y pruebas sustentan la evidencia. El cambio histórico no archivado se conserva íntegro. La capacidad se documenta como `ADDED` por ausencia de especificación canónica y no como delta `MODIFIED`.

## Goals / Non-Goals

**Goals:** preservar US-4.1, US-4.2, US-4.4 y US-5.1–US-5.4, control transaccional del inventario, confirmación y devolución idempotentes; integrar la fila simplificada y mantener comunicación segura ante fallos.

**Non-Goals:** compras, pagos, reportes comerciales, caché de autorizaciones, transacciones distribuidas, preautorización, generaciones, políticas versionadas u outbox para fila. Se conserva el outbox existente de disponibilidad.

## Decisions

### Inventario y resumen

MySQL, Flyway y validación de esquema de Hibernate mantienen entidades existentes. Los asientos numerados se cuentan por estado y los sectores no numerados usan contadores; el resumen incluye capacidad y cantidades disponibles, reservadas y vendidas. `cantidadReservada` y `cantidadVendida` son nombres implementados propuestos, no contratos grupales confirmados. El agotamiento se deriva de ventas confirmadas, no de reservas temporales.

### Reserva, límites y concurrencia

El cliente se identifica por `X-User-Id` y la clave es `(usuarioId, solicitudId)`, cuya huella incluye evento y contenido. Se recupera primero la reserva idéntica vigente con 200; una solicitud nueva responde 201 y una repetición incompatible o no vigente 409. Las reservas duran diez minutos. MS2 consulta `GET /internal/eventos/{eventoId}` a MS1, exige booleano explícito `requiereFila`, y solo si es verdadero llama a `POST /internal/filas/eventos/{eventoId}/consumos` con usuario, solicitud y huella intactos. Antes de toda escritura se valida la autorización sin fallback permisivo.

Las llamadas remotas preceden a la transacción local. Tras bloquear el evento se repite la consulta idempotente; para una reserva nueva con fila se comprueba nuevamente el vencimiento de la autorización. Los bloqueos, restricciones y actualizaciones condicionales de MySQL impiden sobreventa con varias instancias. Cada reintento local repite validaciones sin repetir consumo dentro de la transacción. Al calcular el límite se excluyen unidades de reservas `VIGENTE` cuyo `expiraEn` ya llegó, con lectura actual y sin alterar contadores ni inventario. El scheduler libera físicamente en transacciones separadas y descuenta una sola vez; la consulta de reserva requiere considerar `expiraEn` además del estado persistido.

### Seguridad y resiliencia

El Gateway valida JWT y propaga `X-User-Id` y `X-User-Roles`; creación exige CLIENTE y varias funciones internas no requieren JWT de usuario. MS2 responde 401 a identidad inválida y 403 a rol insuficiente o ausente, precisión pendiente de formalización grupal. Las rutas internas requieren red privada; sus rutas históricas no todas empiezan con `/internal`. Se propaga `X-Correlation-Id` con MDC y limpieza al final de la petición; el outbox asíncrono no hereda contexto de una solicitud anterior.

Resilience4j usa circuitos separados MS1/MS7; respuestas inválidas cuentan como fallos, errores de negocio definitivos se ignoran y solo GET transitorio tiene reintento limitado. Los timeouts predeterminados son conexión 2 s y lectura 5 s. No hay retry automático de escritura ni fallback que eluda fila o confirme un resultado incierto.

## Risks / Trade-offs

- La desactivación o cancelación en MS7 posterior a una autorización no se propaga atómicamente a MS2; se conservan controles locales y de vencimiento.
- Un timeout de consumo no demuestra fracaso y no restaura el turno. La misma clave y huella permiten recuperar el consumo, siempre sujeta a vigencia para una reserva nueva.
- Contadores retenidos e inventario pueden diferir de la cantidad vigente lógica hasta el scheduler; el cálculo del límite excluye vencidas sin descontar dos veces.
- La consulta de una reserva puede devolver estado persistido `VIGENTE` después de `expiraEn`; los consumidores deben comprobar ambos datos.
- El mecanismo existente del outbox intenta respetar orden por evento pero no garantiza orden absoluto ante envíos demorados; no añade versionado.

## Validation and Dependencies

Evidencia registrada: 300 pruebas MS2 sin omisiones, 59 de MS7, y Compose con dos instancias reales de cada servicio, bases separadas, MS1 simulado y proxy de fallos. No equivalen a validar MS1 ni Gateway reales. La confirmación de nombres del resumen, integración real, reglas sobre roles desconocidos y pruebas grupales T-11–T-13, T-18–T-22 permanecen pendientes.
