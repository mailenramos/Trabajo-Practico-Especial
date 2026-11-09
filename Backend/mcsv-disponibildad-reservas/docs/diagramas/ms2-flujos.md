# Flujos principales de MS2

Los diagramas describen las interacciones actualmente documentadas. Las llamadas internas entre microservicios son REST/JSON directas, fuera del Gateway y sin autenticación de servicio. La autorización del cliente corresponde al Gateway, que propaga `X-User-Id` a MS2.

## Inicialización y consulta de disponibilidad

```mermaid
sequenceDiagram
    participant MS1 as MS1 Eventos
    participant MS2 as MS2 Disponibilidad
    participant DB as MySQL de MS2
    MS1->>MS2: PUT /disponibilidad/eventos/{eventoId}\nlimite, sectores y asientos
    MS2->>DB: Persistir evento, sectores y asientos en una transacción
    DB-->>MS2: Commit
    MS2-->>MS1: 204
    MS1->>MS2: GET /disponibilidad/eventos/{eventoId}
    MS2->>DB: Leer disponibilidad local
    DB-->>MS2: Capacidad, cantidades y asientos libres
    MS2-->>MS1: 200 + disponibilidad
```

La inicialización deja el evento en `PENDIENTE_PUBLICACION`. MS1 publica ventas enviando `ACTIVA` mediante el endpoint de estado de venta; no hay una notificación adicional de publicación.

## Creación y confirmación de una reserva

```mermaid
sequenceDiagram
    actor Cliente
    participant GW as API Gateway
    participant MS2 as MS2 Disponibilidad
    participant DB as MySQL de MS2
    participant MS3 as MS3 Compras
    participant MS4 as MS4 Pagos
    Cliente->>GW: Solicitar reserva
    GW->>MS2: POST /disponibilidad/eventos/{eventoId}/reservas\nX-User-Id + solicitudId + detalles
    MS2->>DB: Validar límite e inventario; guardar reserva y contadores
    DB-->>MS2: Commit
    MS2-->>GW: 201 (o 200 para retry idéntico vigente)
    GW-->>Cliente: Resultado de reserva
    Cliente->>MS3: Iniciar compra con reservaId
    MS3->>MS2: GET /disponibilidad/reservas/{reservaId}
    MS2-->>MS3: Titular, estado, vencimiento y detalles
    MS3->>MS4: Procesar pago
    MS4-->>MS3: Pago aprobado
    MS3->>MS2: POST /disponibilidad/reservas/{reservaId}/confirmacion
    MS2->>DB: Confirmar y mover reservado a vendido atómicamente
    DB-->>MS2: Commit
    MS2-->>MS3: 204
```

Si el pago fue aprobado pero MS2 no puede confirmar la reserva, MS3 coordina el tratamiento compensatorio con MS4; MS2 no procesa pagos ni compensaciones.

## Liberación por vencimiento o pago rechazado

```mermaid
sequenceDiagram
    participant Scheduler as Expirador MS2
    participant MS3 as MS3 Compras
    participant MS2 as MS2 Disponibilidad
    participant DB as MySQL de MS2
    alt Venció el plazo
        Scheduler->>MS2: Buscar reservas VIGENTE con expiraEn <= ahora
        MS2->>DB: Por reserva, liberar inventario y contador en una transacción
        DB-->>MS2: Commit
    else Pago rechazado
        MS3->>MS2: POST /disponibilidad/reservas/{reservaId}/liberacion\nPAGO_RECHAZADO
        MS2->>DB: Liberar reserva vigente y contador en una transacción
        DB-->>MS2: Commit
        MS2-->>MS3: 204
    end
```

La expiración y la liberación manual usan el bloqueo de la reserva para evitar transiciones dobles. Las cantidades confirmadas y las ventas no se alteran en estos dos casos.

## Devolución parcial aprobada

```mermaid
sequenceDiagram
    participant MS3 as MS3 Compras y devoluciones
    participant MS5 as MS5 Tickets
    participant MS2 as MS2 Disponibilidad
    participant DB as MySQL de MS2
    participant MS4 as MS4 Pagos
    MS3->>MS5: Invalidar los tickets devueltos
    MS5-->>MS3: Resultado de invalidación
    MS3->>MS2: POST /disponibilidad/reservas/{reservaId}/liberacion\nDEVOLUCION_APROBADA + devolucionId + detalles
    MS2->>DB: Validar titularidad de detalles e idempotencia global
    MS2->>DB: Liberar solo los detalles indicados y ajustar vendidos/confirmados
    DB-->>MS2: Commit
    MS2-->>MS3: 204 (también para retry idéntico)
    MS3->>MS4: Coordinar el reintegro
```

El estado de la reserva sigue `CONFIRMADA`. Un `devolucionId` ya aplicado con otra reserva o contenido distinto, o un detalle ya devuelto con otro identificador, produce conflicto sin repetir cambios.

## Notificación de disponibilidad agotada/restaurada por Outbox

```mermaid
sequenceDiagram
    participant MS2 as MS2 Disponibilidad
    participant DB as MySQL de MS2
    participant Worker as Worker Outbox MS2
    participant MS1 as MS1 Eventos
    MS2->>DB: Registrar transición agotado/disponible en la transacción de inventario
    Worker->>DB: Reclamar notificación pendiente respetando orden por evento
    Worker->>MS1: PUT /eventos/{eventoId}/disponibilidad\n{agotado: true|false}
    MS1-->>Worker: 204
    Worker->>DB: Marcar como entregada
    Note over MS2,DB: Sin cambio real no se agrega notificación. Inicializar no notifica.
```

El agotamiento se determina por ventas confirmadas, no por reservas temporales, suspensión, cancelación o finalización del evento.
