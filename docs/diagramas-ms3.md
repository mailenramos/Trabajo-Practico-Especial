# TicketYa — Microservicio 3 (Compras y Devoluciones): diagramas

Diagramas de las Entregas 1 y 2, generados a partir de las entidades y los servicios del código en `Backend/mcsv-compras-devoluciones/`. Los archivos fuente (`.mmd`) y las imágenes (`.png`) están en esta carpeta; GitHub y VS Code (con una extensión de Mermaid) muestran los bloques de abajo directamente.

## 1. Diagrama de secuencia de la compra

![Secuencia de la compra](diagramas-ms3/secuencia-compra.png)

Cubre el camino feliz y sus ramas: reserva no vigente, pago rechazado, venta rechazada después del cobro (con reintegro automático), evento cerrado al emitir (Entrega 2: compra fallida con reintegro automático y aviso de evento cancelado) y emisión de entradas fallida (la compra queda pendiente y el pago responde 202). Antes de repetir una emisión sin respuesta, se consultan las entradas de la compra.

<details><summary>Fuente Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant MS3 as Compras (MS3)
    participant MS1 as Eventos (MS1)
    participant MS2 as Reservas (MS2)
    participant MS4 as Pagos (MS4)
    participant MS5 as Entradas (MS5)

    Note over C,MS2: El cliente crea la reserva directo en Reservas (por el Gateway)
    C->>MS2: POST reserva (solicitudId)
    MS2-->>C: reservaId

    rect rgb(235, 242, 250)
    Note over C,MS1: 1. Iniciar la compra
    C->>MS3: POST /compras {reservaId}
    MS3->>MS2: GET /disponibilidad/reservas/{reservaId}
    MS2-->>MS3: reserva (clienteId, estado, expiraEn, detalles)
    MS3->>MS1: GET /internal/eventos/{eventoId}
    MS1-->>MS3: evento, tipo y precios de sectores
    MS3->>MS3: calcula total y registra la compra PENDIENTE con su tipoEvento (una fila por lugar)
    MS3-->>C: 201 compra pendiente
    end

    rect rgb(238, 247, 238)
    Note over C,MS5: 2. Pagar
    C->>MS3: POST /compras/{id}/pago (Idempotency-Key, medioDePago)
    MS3->>MS3: registra la clave (cliente + clave) y valida el medio de pago
    MS3->>MS2: GET reserva (verificación preventiva)
    alt reserva vencida o liberada
        MS3-->>C: 409 RESERVA_NO_VIGENTE (no se cobra)
    else reserva vigente
        MS3->>MS4: POST /internal/pagos (Idempotency-Key = claveCobro)
        alt pago RECHAZADO
            MS4-->>MS3: 201 status RECHAZADO
            MS3->>MS3: compra PENDIENTE a FALLIDA
            MS3->>MS2: POST liberacion (PAGO_RECHAZADO)
            MS3-->>C: 200 compra fallida
        else pago APROBADO
            MS4-->>MS3: 201 status APROBADO (paymentId)
            MS3->>MS3: guarda pagoId
            MS3->>MS2: POST confirmacion
            alt reserva vencida o liberada (409)
                MS3->>MS3: compra PENDIENTE a FALLIDA
                MS3->>MS4: POST /internal/pagos/{pagoId}/reintegros (total, "Reserva vencida")
                MS3-->>C: 200 compra fallida con su reintegro
            else venta confirmada (204)
                Note over MS3,MS5: Toda llamada a Entradas lleva X-Service-Token y X-Correlation-Id
                loop hasta 3 intentos
                    opt la emisión anterior quedó sin respuesta
                        MS3->>MS5: GET /internal/compras/{idCompra}/entradas
                        MS5-->>MS3: entradas ya emitidas (si hay, se asignan sin emitir)
                    end
                    MS3->>MS5: POST /internal/entradas/emision (idCompra)
                end
                alt entradas emitidas
                    MS5-->>MS3: idEntrada, idSector, fila, numero, codigoQr
                    MS3->>MS3: guarda entradaId, compra a CONFIRMADA y, en la misma transacción, los avisos COMPRA_CONFIRMADA y ENTRADAS_DISPONIBLES (outbox)
                    MS3-->>C: 200 compra confirmada con sus entradas
                else evento cerrado (409 EVENTO_CERRADO, sin reintentos)
                    MS5-->>MS3: 409 EVENTO_CERRADO
                    MS3->>MS3: compra PENDIENTE a FALLIDA y, en la misma transacción, el reintegro por el total (CANCELACION_EVENTO) y el aviso EVENTO_CANCELADO (outbox)
                    MS3->>MS4: POST /internal/pagos/{pagoId}/reintegros (total, "Evento cancelado")
                    MS3-->>C: 200 compra fallida con su reintegro
                else falla las 3 veces
                    MS3->>MS3: la compra queda PENDIENTE
                    MS3-->>C: 202 operación no terminada (repetir el pago con la misma clave)
                end
            end
        end
    end
    end
```

</details>

## 2. Diagrama de secuencia de la modificación de un evento y del envío de avisos (Entrega 2)

![Secuencia de la modificación de un evento y del envío de avisos](diagramas-ms3/secuencia-modificacion-y-avisos.png)

Eventos avisa el cambio de fecha, horario o lugar; este servicio lo aplica en una sola transacción, idempotente por evento y versión, y deja un aviso por comprador en el outbox. La tarea de envío reparte después esos avisos (y los de compras, devoluciones, reintegros y cancelaciones) a Notificaciones, con reintentos.

<details><summary>Fuente Mermaid</summary>

```mermaid
sequenceDiagram
    autonumber
    participant MS1 as Eventos (MS1)
    participant MS3 as Compras (MS3)
    participant BD as Base de MS3
    participant MS9 as Notificaciones (MS9)

    rect rgb(235, 242, 250)
    Note over MS1,BD: 1. Modificación de un evento (Entrega 2)
    MS1->>MS3: POST /internal/eventos-modificados (X-Service-Token) {eventoId, version, fechaHora?, lugar?, ciudad?}
    alt token ausente o incorrecto
        MS3-->>MS1: 403 SERVICIO_NO_AUTORIZADO
    else cuerpo inválido
        MS3-->>MS1: 400 VALIDACION (campos)
    else versión ya procesada o anterior a la mayor procesada
        MS3-->>MS1: 200 YA_PROCESADA o VERSION_ANTERIOR (no cambia nada)
    else versión nueva
        Note over MS3,BD: una sola transacción (todo o nada)
        MS3->>BD: inserta modificaciones_evento (evento_id, version) único
        opt vino fechaHora
            MS3->>BD: UPDATE eventoFechaInicio en compras y solicitudes_devolucion del evento
        end
        MS3->>BD: un aviso EVENTO_MODIFICADO por cliente con compra CONFIRMADA
        MS3-->>MS1: 202 ACEPTADA
    end
    end

    rect rgb(238, 247, 238)
    Note over MS3,MS9: 2. Envío de avisos (tarea programada, cada 10 s)
    MS3->>BD: recupera los PROCESANDO vencidos (a PENDIENTE o ERROR_FINAL)
    MS3->>BD: toma cada aviso listo: PENDIENTE a PROCESANDO si nadie lo tomó (toma condicional)
    MS3->>MS9: POST /internal/notificaciones (X-Service-Token) {acontecimientoId, tipo, versionEsquema, ...}
    alt 2xx (nuevo o duplicado)
        MS9-->>MS3: 202
        MS3->>BD: ENVIADO
    else 400, 415 o 422 (rechazo definitivo)
        MS9-->>MS3: error de validación
        MS3->>BD: ERROR_FINAL
    else sin respuesta, 403 u otro error
        MS3->>BD: PENDIENTE con intentos + 1 y espera creciente (30 s, 60 s, 120 s...), o ERROR_FINAL si agotó los 5 intentos
    end
    Note over MS3,MS9: La operación que generó el aviso ya terminó: una falla de Notificaciones nunca la revierte
    end
```

</details>

## 3. Diagrama entidad-relación

![Entidad-relación del microservicio 3](diagramas-ms3/er-ms3.png)

Notas de lectura:

- La única clave foránea real es `detalles_compra.compra_id` hacia `compras.id`. Las demás relaciones son referencias lógicas por identificador (`compraId`, `reintegroId`, `eventoId`): este servicio no tiene claves foráneas hacia datos de otros servicios y tampoco las usa entre sus propias tablas de coordinación.
- `claves_idempotencia` tiene clave primaria compuesta (`cliente_id`, `clave`): la clave solo vale para el cliente que la envió.
- `solicitudes_devolucion.id` es el `devolucionId` y se usa como `refundRequestId` del reintegro de esa devolución.
- `solicitudes_devolucion.entradaActiva` tiene restricción única: es la copia del `entradaId` mientras la solicitud está pendiente o aprobada, y queda `NULL` al rechazarse. Así una entrada tiene a lo sumo una solicitud activa.
- `reintegros.origen` distingue una devolución, una cancelación de evento y una venta rechazada después del cobro. En una cancelación, `entradas_de_reintegro` guarda qué entradas cubre cada reintegro y si ya pasaron a devueltas.
- `fila` y `numero` son enteros y quedan nulos en los sectores no numerados.
- `compras.tipoEvento` (Entrega 2) se copia de Eventos al iniciar la compra; las compras anteriores quedan con el tipo nulo y no cuentan en el historial de preferencias.
- `modificaciones_evento` tiene restricción única (`evento_id`, `version`): la misma modificación se aplica una sola vez.
- `avisos_pendientes` es el outbox hacia Notificaciones. Su restricción única (`acontecimiento_id`, `tipo`, `usuario_id`) es la misma clave con la que Notificaciones deduplica: los avisos de un evento a cada comprador comparten `acontecimiento_id`. `recurso_id` apunta a la compra, la devolución, el reintegro o el evento, según el tipo.

<details><summary>Fuente Mermaid</summary>

```mermaid
erDiagram
    compras ||--o{ detalles_compra : "tiene (FK)"
    compras ||--o{ claves_idempotencia : "compraId"
    compras ||--o{ solicitudes_devolucion : "compraId"
    compras ||--o{ reintegros : "compraId"
    solicitudes_devolucion ||--o| reintegros : "id = refundRequestId"
    reintegros ||--o{ entradas_de_reintegro : "reintegroId"
    cancelaciones_evento ||--o{ compras : "eventoId"
    modificaciones_evento ||--o{ compras : "eventoId"
    compras ||--o{ avisos_pendientes : "recursoId (o el de la devolución, reintegro o evento)"

    compras {
        UUID id PK
        string estado "PENDIENTE, CONFIRMADA, FALLIDA"
        string clienteId
        string reservaId UK
        string eventoId
        string eventoNombre
        datetime eventoFechaInicio "se actualiza si el evento cambia de fecha"
        string tipoEvento "copiado de Eventos; nulo en compras anteriores"
        decimal total
        string medioDePago
        string pagoId "ID del pago en Pagos"
        boolean ventaConfirmada
        datetime fecha
    }
    detalles_compra {
        long id PK
        UUID compra_id FK
        string sectorId
        string sectorNombre
        int fila "nulo si no es numerado"
        int numero "nulo si no es numerado"
        decimal precioUnitario
        string entradaId UK "ID en Entradas"
    }
    claves_idempotencia {
        string cliente_id PK
        string clave PK
        UUID compraId
        string huellaPedido
        string claveCobro "Idempotency-Key hacia Pagos"
        string estado "EN_PROCESO, COMPLETADA, INTERRUMPIDA"
        text resultado
        datetime creadaEn
        datetime tomadaEn
    }
    solicitudes_devolucion {
        UUID id PK "devolucionId"
        string clienteId
        string entradaId
        UUID compraId
        string sectorId
        string sectorNombre
        int fila
        int numero
        decimal monto "precio unitario"
        string eventoId
        string eventoNombre
        datetime eventoFechaInicio
        string estado "PENDIENTE, APROBADA, RECHAZADA"
        string motivoRechazo
        datetime creadaEn
        datetime resueltaEn
        string resueltaPor
        string entradaActiva UK "NULL si se rechaza"
        boolean entradaEnReintegro
        boolean lugarLiberado
        boolean entradaDevuelta
        string ultimoError
    }
    reintegros {
        UUID id PK
        UUID compraId
        UUID refundRequestId UK
        string pagosReintegroId "ID en Pagos"
        decimal monto
        string motivo
        string origen "DEVOLUCION, CANCELACION_EVENTO, VENTA_RECHAZADA"
        string estado "PENDIENTE, EXITOSO, FALLIDO"
        int intentos
        string ultimoError
        datetime creadoEn
        datetime actualizadoEn
    }
    entradas_de_reintegro {
        long id PK
        UUID reintegroId "UK junto a entradaId"
        string entradaId "UK junto a reintegroId"
        boolean devuelta
    }
    cancelaciones_evento {
        string eventoId PK
        string motivo
        datetime canceladoEn
        datetime recibidaEn
        string estado "EN_PROCESO, PROCESADA"
        datetime tomadaEn
        int entradasMarcadas
        string ultimoError
    }
    modificaciones_evento {
        long id PK
        string evento_id "UK junto a version"
        int version "UK junto a evento_id"
        datetime recibida_en
    }
    avisos_pendientes {
        long id PK
        string acontecimiento_id "UK con tipo y usuario_id"
        string tipo "UK; COMPRA_CONFIRMADA, EVENTO_MODIFICADO, ..."
        string usuario_id "UK; destinatario"
        string recurso_id
        string correlacion_id
        json datos
        string estado "PENDIENTE, PROCESANDO, ENVIADO, ERROR_FINAL"
        int intentos
        datetime proximo_intento
        string ultimo_error
        datetime creado_en
        string toma "ejecución que lo tomó"
        datetime vence_en "límite de PROCESANDO"
        datetime enviado_en
    }
```

</details>
