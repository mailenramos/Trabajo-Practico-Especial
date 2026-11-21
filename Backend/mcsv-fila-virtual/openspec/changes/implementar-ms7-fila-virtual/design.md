## Context

Este documento es retrospectivo, no una propuesta anterior al desarrollo. Las fuentes normativas son la consigna, la sección 1.2 simplificada de la arquitectura y las historias US-7.1–US-7.9 de `documentacion/User-Stories-Entrega-2.md`. Código, OpenAPI y pruebas aportan evidencia. El diseño completo de la propuesta es evolución futura y no se aplica a esta entrega.

## Goals / Non-Goals

**Goals:** una FIFO por evento, turnos persistentes, cupo y vencimiento configurables, consumo atómico idempotente, controles administrativos y operación segura con múltiples instancias.

**Non-Goals:** inventario o pagos, preautorización en dos fases, registro adicional de operaciones, estados preautorizado/cancelado de turno, generaciones, políticas versionadas, transacciones distribuidas y outbox para la fila.

## Decisions

### Persistencia, turnos y scheduler

`FilaEvento` relaciona un evento externo con estado `ACTIVA`, `PAUSADA` o `DESACTIVADA`, cupo, duración y `cancelado`. `Turno` pertenece a una fila y conserva usuario, orden persistente, estado `ESPERANDO`, `HABILITADO`, `CONSUMIDO` o `VENCIDO`, fechas y datos de consumo. MySQL propio, Flyway y restricciones de unicidad impiden más de un turno activo por cliente/evento y reutilizar `(usuarioId, solicitudId)` entre eventos. Las referencias externas no son FK entre servicios.

El ID persistido establece FIFO por evento y la posición cuenta turnos esperando anteriores más uno. El cupo inicial es 10 y la duración 5 minutos. Aumentar cupo habilita por orden; reducirlo no revoca habilitados; cambios de duración rigen para nuevas habilitaciones. El scheduler configurable con `fixedDelay` de 60 s detecta tanto habilitados vencidos como filas activas con espera y cupo. Procesa cada fila en transacción independiente bajo bloqueo pesimista, vuelve a comprobar estado, registra errores y sigue con las demás. La expiración se verifica también en operaciones locales; no se promete ejecución puntual a los 60 s.

### Consumo y coordinación

MS2 envía usuario, solicitud y huella a `POST /internal/filas/eventos/{eventoId}/consumos`. La transacción bloquea la fila, comprueba autorización y cambia el turno a consumido una vez; una repetición idéntica recupera el consumo histórico y la fecha original, y claves incompatibles producen conflicto. La autorización para crear una reserva nueva exige vencimiento futuro, fila no desactivada y evento no cancelado. El consumo libera cupo aunque falle la reserva. Ante conflicto de unicidad, la recuperación ocurre tras rollback en una nueva transacción. Los consumos históricos no prueban que exista una reserva.

MS1 es autoridad de `requiereFila`. Activar prepara la fila, actualiza MS1 a verdadero y solo tras confirmación activa localmente; desactivar cierra e invalida localmente antes de actualizar MS1 a falso. Pausa y reanudación no cambian política. No se mantienen transacciones durante HTTP. Un resultado incierto no se comunica como éxito y se reintenta la misma operación; no se garantiza el orden de solicitudes administrativas opuestas concurrentes o demoradas. Cancelación local es idempotente, invalida activos, conserva consumidos y bloquea futuras activaciones incluso si no había fila previa.

### API, identidad y resiliencia

Cinco endpoints públicos de `/filas/eventos/{eventoId}` ofrecen ingreso, mi-turno, consulta, configuración y estado; dos internos son `POST /internal/filas/eventos/{eventoId}/consumos` y `PUT /internal/filas/eventos/{eventoId}/cancelacion`. El Gateway valida JWT y propaga `X-User-Id` y roles; MS7 verifica CLIENTE u ORGANIZADOR propietario/ADMIN y confía en la red interna para endpoints internos. 401 por identidad inválida y 403 por roles insuficientes o ausentes son una precisión acordada pendiente de formalización grupal. Se conserva el formato de errores de la Entrega 1.

La correlación usa MDC y `X-Correlation-Id`, se propaga a MS1 y se limpia al finalizar. El registro HTTP incluye usuario solo tras validar identidad y rol, además de método, operación, estado y duración. La centralización de logs sigue pendiente grupalmente. Resilience4j mantiene circuitos separados para GET de evento y PUT de política, timeouts configurables 2 s/5 s y retry limitado solo en GET transitorio. No hay fallback permisivo ni retry automático de escritura.

## Risks / Trade-offs

- Un consumo exitoso puede quedar sin reserva por fallo posterior; ante incertidumbre se reutilizan clave y huella y el cliente puede reingresar después de fallo definitivo.
- Una desactivación o cancelación posterior a la autorización puede no reflejarse antes del commit de MS2; no existe transacción distribuida.
- Fila pausada recupera turnos activos, no crea ni habilita otros y permite consumir habilitados vigentes. Una fila desactivada o cancelada no autoriza nuevas reservas mediante consumo histórico.
- El estado de los circuitos es local a cada instancia. Los roles desconocidos mezclados con válidos requieren acuerdo grupal.

## Validation and Dependencies

Se registran 59 pruebas de MS7 y 300 de MS2 sin omisiones y Compose aislado con dos instancias reales de cada uno y dos bases independientes. MS1 está simulado y el proxy solo simula fallos; no validan sistema grupal real. Pendientes MS1 real (política, propietario y cancelación), Gateway/MS6, seguridad integrada, T-19 grupal, JMeter T-21 y observabilidad/despliegue grupales.
