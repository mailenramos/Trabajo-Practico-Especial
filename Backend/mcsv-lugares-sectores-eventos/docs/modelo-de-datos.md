# Modelo de datos

Este servicio persiste cuatro colecciones MongoDB propias: `lugares`, `eventos`, `outbox` y `personal_acceso`. Los sectores son subdocumentos embebidos; no tienen colección propia. En el diagrama, `PK` indica la clave del documento o la clave estable del subdocumento, no una restricción de clave foránea de MongoDB.

```mermaid
erDiagram
    LUGAR {
        string _id PK "String hexadecimal de ObjectId"
        string nombre
        string direccion
        string ciudad
        double latitud
        double longitud
        Long version
    }

    SECTOR_LUGAR {
        string id PK "UUID; clave del subdocumento"
        string nombre
        TipoSector tipo
        Integer filas "nullable"
        Integer asientosPorFila "nullable"
        Integer capacidad "nullable"
    }

    EVENTO {
        string _id PK "String hexadecimal de ObjectId"
        string organizadorId "ID recibido en X-User-Id"
        string lugarId "ID de LUGAR; referencia lógica"
        string ciudad
        string nombre
        string descripcion
        string tipo
        string[] participantes "Java List<String>"
        Instant fechaHora
        EstadoEvento estado
        boolean ventaSuspendida
        boolean requiereFila
        boolean cancelacionPendiente
        int limiteEntradasPorCliente
        BigDecimal precioMinimo "nullable hasta publicar"
        string motivoCancelacion "nullable"
        Instant canceladoEn "nullable"
        Long version
    }

    SECTOR_EVENTO {
        string sectorId PK "UUID copiado de SECTOR_LUGAR.id"
        string nombre
        TipoSector tipo
        Integer filas "nullable"
        Integer asientosPorFila "nullable"
        Integer capacidad "nullable"
        BigDecimal precio "nullable hasta asignarlo"
    }

    AVISO_OUTBOX {
        string _id PK "ID estable; único"
        string tipo
        string eventoId "referencia lógica al evento"
        string correlationId "nullable"
        string destino
        string metodoHttp "nullable; POST por defecto"
        string motivoCancelacion "nullable"
        object payload
        EstadoAvisoOutbox estado
        int intentos
        Instant proximoIntento
        string ultimoError "nullable"
        Instant creadoEn
        Instant actualizadoEn
        Instant leaseHasta "nullable"
        Instant entregadoEn "nullable"
    }

    ASIGNACION_PERSONAL_ACCESO {
        string _id PK
        string eventoId "referencia lógica al evento"
        string usuarioId "UUID de MS6"
        EstadoPersonalAcceso estado
        Instant asignadaEn
        Instant revocadaEn "nullable"
    }

    LUGAR ||--o{ SECTOR_LUGAR : "sectores embebidos"
    LUGAR ||--o{ EVENTO : "evento.lugarId referencia lugar._id"
    EVENTO ||--o{ SECTOR_EVENTO : "sectores embebidos"
    EVENTO ||--o{ AVISO_OUTBOX : "eventoId referencia lógica"
    EVENTO ||--o{ ASIGNACION_PERSONAL_ACCESO : "eventoId referencia lógica"
```

`LUGAR._id` y `EVENTO._id` son propiedades Java `String` marcadas con `@Id`; el código las crea como hexadecimal de `ObjectId`. `SECTOR_LUGAR.id` se genera como UUID en formato `String`. Al crear un evento se copia ese valor a `SECTOR_EVENTO.sectorId`. `TipoSector` admite `NUMERADO` y `NO_NUMERADO`; `EstadoEvento` admite `BORRADOR`, `PUBLICADO`, `AGOTADO`, `CANCELADO` y `FINALIZADO` (la API los serializa en minúsculas).

`Lugar.version` y `Evento.version` llevan `@Version` para control optimista de concurrencia. La versión persistida de un evento publicado o agotado se incluye en el aviso de modificación a Compras; `requiereFila` es la política vigente que mantiene MS1, y `cancelacionPendiente` marca una intención de cancelación todavía no confirmada por Reservas.

La relación `evento.lugarId` es una referencia por ID entre documentos de este mismo servicio. Se resuelve mediante `LugarRepository`; no hay una clave foránea ni una relación gestionada por MongoDB. Del mismo modo, `eventoId` en outbox y `personal_acceso` es lógico. Las listas de sectores, en cambio, forman parte de cada documento.

La colección `outbox` guarda avisos de cambio a otros servicios. `eventoId` es una referencia lógica, no una clave foránea. Además de los avisos `PUBLICACION`, `CANCELACION`, `CANCELACION_FILA` y `MODIFICACION`, guarda el trabajo `CANCELACION_RESERVAS` que representa una intención durable de cancelación. La intención también se refleja en `Evento.cancelacionPendiente` mientras espera la confirmación de Reservas. Los avisos transitan por `PENDIENTE`, `PROCESANDO`, `ENTREGADO` y `ERROR_FINAL`; `leaseHasta` marca el vencimiento de una reclamación. `metodoHttp` y `payload` son opcionales; sin método, la entrega usa `POST`.

Los avisos de publicación, modificación y cancelación confirmada tienen IDs estables como `publicacion-{eventoId}`, `modificacion-{eventoId}-v{version}` y `cancelacion-{eventoId}`. El de confirmación de cancelación usa `cancelacion-reservas-{eventoId}` y el de fila `cancelacion-fila-{eventoId}`. `correlationId` se persiste cuando existe para conservar la trazabilidad durante los reintentos. El backfill de arranque migra documentos legacy que aún tengan el antiguo campo `avisoCancelacionPendiente`; el campo vigente del documento `Evento` es `cancelacionPendiente`.

`ASIGNACION_PERSONAL_ACCESO` representa una asignación por evento y usuario de MS6. `usuarioId` es el UUID textual devuelto por MS6, no una referencia a una colección local. La asignación se conserva al revocarla (`estado=REVOCADA`) y puede reactivarse.

## Relaciones con otros servicios

Las rutas de gestión pública llegan por el API Gateway; las rutas internas y las llamadas salientes son HTTP/JSON directas entre servicios, sin pasar por el Gateway. Las referencias entre servicios son IDs: no hay claves foráneas ni lecturas directas a bases de datos ajenas.

| Dirección | Dato | Comportamiento verificado en este repositorio |
|---|---|---|
| Gateway → este servicio | Identidad y rutas de catálogo/eventos/personas | Las rutas públicas incluyen búsqueda y detalle de eventos; las rutas de gestión protegidas propagan `X-User-Id` y `X-User-Roles`. El Gateway valida el JWT. |
| Este servicio → Reservas | `eventoId`, `sectorId`, `fila`, `numero`, estado de venta | Al publicar, se inicializa disponibilidad; la consulta de detalle pide disponibilidad; suspensión/reactivación y cancelación cambian el estado de venta. Para sectores numerados se generan coordenadas de asientos. Reservas llama de vuelta a `PUT /eventos/{eventoId}/disponibilidad` para actualizar la proyección local. |
| Compras → este servicio | `eventoId` | Compras consulta `GET /internal/eventos/{eventoId}`. La respuesta incluye el ID del evento y cada `sectorId`, además del nombre del evento, nombre del sector y precio. |
| Preferencias (MS8) → este servicio | Eventos publicados o agotados | MS8 consulta `GET /internal/eventos-publicados` directamente, sin headers de usuario. La ruta es interna, no se publica por el API Gateway y devuelve una respuesta paginada ordenada por fecha e ID. |
| Este servicio → Compras | Avisos `CANCELACION` y `MODIFICACION` | Outbox envía `POST /eventos-cancelados` y `POST /internal/eventos-modificados` con ID de aviso estable. La modificación reporta la versión confirmada del evento. |
| Este servicio → MS7 Fila virtual | Aviso `CANCELACION_FILA` | Outbox envía `PUT /internal/filas/eventos/{eventoId}/cancelacion` después de confirmar la cancelación en MS1. |
| Este servicio → Preferencias (MS8) | Aviso `PUBLICACION` | Después de confirmar Reservas, MS1 persiste evento y aviso en una transacción; luego envía `POST /internal/preferencias/eventos-publicados` con el ID estable en `X-Aviso-Id`. |
| Este servicio → Usuarios (MS6) | `usuarioId`, estado y roles | Para asignar personal consulta `GET /internal/usuarios/por-correo`; exige usuario activo con rol `PERSONAL_ACCESO` y guarda el UUID retornado. La llamada usa `X-Service-Token` y el circuito `usuarios`. |
| Fila virtual (MS7) → este servicio | `requiereFila` | Actualiza directamente `PUT /internal/eventos/{eventoId}/politica-fila`; la ruta no se publica por el Gateway. |
| Entradas (MS5) → este servicio | Autorización de una asignación | Consulta directamente `GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion`; devuelve el estado actual sin caché ni headers de usuario. |

**Límite de verificación:** este repositorio no contiene el código de persistencia ni de compra de Reservas o Compras. El cliente de este servicio y el stub de Postman permiten verificar los datos enviados a Reservas; el endpoint interno permite verificar los datos que este servicio entrega a Compras. No permiten confirmar, por sí solos, qué campos guardan las implementaciones reales de esos otros servicios. Por eso, la copia de `eventoId`, `sectorId` y `(fila, numero)` en Reservas, y la copia de nombre/precio en Compras, no se afirman aquí como persistencias externas verificadas.

## Entidades y datos solapados entre bases

| Dato compartido | Fuente de verdad y copia |
|---|---|
| `eventos.ciudad` | El lugar aporta la ciudad al crear el evento. `EventoService` guarda una copia junto con `lugarId`; no vuelve a derivarla del lugar al buscar eventos. La estructura de sectores también se copia del lugar al crear el evento. Luego, el evento conserva su propia instantánea; cada precio de sector se asigna al evento. |
| Sectores y asientos al publicar | La estructura inicial se toma de `eventos.sectores`. Este servicio envía a Reservas `sectorId`, tipo y capacidad. Para sectores numerados calcula `capacidadTotal = filas × asientosPorFila` y genera los pares `(fila, numero)`; para los no numerados envía la capacidad configurada y una lista de asientos vacía. La persistencia de esa copia en Reservas no se puede comprobar en este repositorio. |
| Nombre del evento, nombre del sector y precio al comprar | La respuesta interna disponible para Compras expone `eventoId`, nombre del evento y, por sector, `sectorId`, nombre y precio. El nombre/precio vigente aquí se mantiene en `eventos` y sus sectores embebidos. Que Compras copie estos valores al registrar una compra no es verificable sin el código de Compras. |
| Estado `AGOTADO` | La disponibilidad de Reservas es la fuente operativa; Reservas notifica el resultado con el callback `agotado`. Este servicio guarda el estado del evento como una proyección local y no recalcula la disponibilidad. El origen y el efecto local del callback están en el contrato y código de este servicio; el almacenamiento interno de Reservas queda fuera del repositorio. |

## Índices

Además de los índices automáticos `_id_` de MongoDB, el código declara los siguientes índices:

| Colección | Índice | Campos | Uso |
|---|---|---|---|
| `lugares` | `idx_lugares_ciudad_nombre` | `ciudad` ascendente, `nombre` ascendente | Índice compuesto para filtros/orden por ciudad y nombre. Las operaciones actuales de lugares no aplican esos filtros. |
| `lugares` | `idx_lugares_nombre` | `nombre` ascendente | Índice disponible para búsquedas por nombre; las operaciones actuales de lugares no filtran por nombre. |
| `eventos` | `idx_eventos_estado_fecha` | `estado`, `fechaHora` ascendentes | Filtrar por estado y ordenar/limitar por fecha. |
| `eventos` | `idx_eventos_estado_ciudad_fecha` | `estado`, `ciudad`, `fechaHora` ascendentes | Búsqueda de eventos por estado y ciudad, ordenada por fecha. |
| `eventos` | `idx_eventos_organizador_estado` | `organizadorId`, `estado` ascendentes | Consultar eventos de un organizador por estado. |
| `eventos` | `organizadorId` (nombre generado) | `organizadorId` ascendente | Filtro por organizador. |
| `eventos` | `idx_eventos_tipo` | `tipo` ascendente | Filtro por tipo de evento. |
| `eventos` | `idx_eventos_participantes` | `participantes` ascendente (multikey) | Filtro por participante. |
| `eventos` | `Evento_TextIndex` | `nombre`, `descripcion`, `participantes` | Búsqueda de texto y orden por relevancia. |
| `outbox` | `_id_` (automático, único) | `_id` ascendente | Impide IDs de aviso duplicados; los avisos usan IDs estables por evento y tipo/versión. |
| `outbox` | `idx_outbox_estado_proximo_intento` | `estado`, `proximoIntento`, `intentos` ascendentes | Reclamar avisos pendientes cuyo próximo intento venció y que no agotaron los intentos. |
| `outbox` | `idx_outbox_estado_lease` | `estado`, `leaseHasta`, `intentos` ascendentes | Recuperar avisos `PROCESANDO` con lease vencido y seleccionar candidatos al barrido de intentos agotados. |
| `personal_acceso` | `idx_personal_acceso_evento_usuario` (único) | `eventoId`, `usuarioId` ascendentes | Garantiza como máximo una asignación conservada por combinación evento/usuario; la reactivación actualiza el documento existente. |

El orden por `precioMinimo` se realiza en las consultas, pero no hay un índice de precio declarado en el modelo. Los índices compuestos ayudan a los filtros/órdenes indicados, aunque el plan elegido depende de la consulta y de MongoDB.

En el perfil `dev`, `DevSampleDataInitializer` inserta eventos de ejemplo directamente en la colección `eventos`, después de cargar los lugares. Copia a cada evento la ciudad y los sectores del lugar (incluidos sus UUID), y asigna precios como `BigDecimal` salvo en el evento borrador. Estos documentos no se publican mediante el flujo de la API y, por lo tanto, no inicializan disponibilidad en Reservas: el detalle puede devolver `disponibilidad: null`, y las operaciones de suspender o cancelar eventos publicados fallan con `503` si Reservas no los conoce. El evento de ejemplo cancelado no genera un aviso outbox al iniciar el servicio.

## Decisiones del modelo

| Decisión | Justificación |
|---|---|
| Embebir los sectores en lugares y eventos | El código los administra como parte de su documento padre y los carga juntos. En el evento se necesita una instantánea del sector del lugar y un precio propio, por lo que un subdocumento permite conservar ambos sin una colección separada. |
| No persistir cada asiento | Para un sector numerado, las coordenadas se derivan de `filas` y `asientosPorFila`; se enumeran al publicar. Así no se guardan filas de asientos estáticos en este servicio. La ocupación/disponibilidad se delega a Reservas. |
| Usar `BigDecimal` y BSON `Decimal128` para precios | `BigDecimal` representa decimales monetarios sin la aproximación binaria de `double`. `MongoDecimalConfiguration` convierte explícitamente entre `BigDecimal` y `Decimal128` al escribir y leer. |
| Copiar la ciudad al evento | `ciudad` participa como filtro de búsqueda de eventos. Guardarla en el evento permite consultar y filtrar eventos sin cargar el lugar por cada resultado; es una copia tomada al crear el evento, no una sincronización automática. |

## Evidencia en código

- Documentos y subdocumentos: `domain/Lugar.java`, `domain/Sector.java`, `domain/Evento.java`, `domain/EventoSector.java`.
- Asignaciones y estados: `domain/AsignacionPersonalAcceso.java`, `domain/EstadoPersonalAcceso.java`, `repository/PersonalAccesoRepository.java`.
- Copia de lugar a evento y de ciudad/sectores: `service/EventoService.java`.
- Generación de sectores de asiento para Reservas y callback: `service/EventoLifecycleService.java`, `api/EventoController.java`.
- Outbox y recuperación de cancelaciones: `domain/AvisoOutbox.java`, `outbox/EventoCancelacionOutboxService.java`, `outbox/OutboxDispatcher.java`.
- Índices MongoDB: anotaciones de `domain/Lugar.java`, `domain/Evento.java`, `domain/AvisoOutbox.java` y `domain/AsignacionPersonalAcceso.java`; pruebas en `LugarRepositoryMongoTest`, `EventoRepositoryMongoTest` y `OutboxAvisoRepositoryMongoTest`.
- Contratos de integración: `api/EventoInternoResponse.java`, `reservas/dto/InicializarDisponibilidadRequest.java` y `reservas/dto/SectorDisponibilidadRequest.java`.
