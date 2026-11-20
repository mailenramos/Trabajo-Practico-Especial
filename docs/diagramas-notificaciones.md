# TicketYa — Gestión de notificaciones: diagramas

Diagramas del servicio de la Entrega 2, generados a partir del código en [`Backend/mcsv-notificaciones/`](../Backend/mcsv-notificaciones). Las fuentes Mermaid (`.mmd`) están en [`diagramas-notificaciones/`](diagramas-notificaciones); GitHub y VS Code (con una extensión de Mermaid) muestran los bloques de abajo directamente.

## 1. Diagrama de secuencia: recepción, envío y recuperación

Cubre la recepción idempotente (403, 400, reentrega y aviso nuevo), la selección atómica con la actualización condicional, el envío con sus fallos temporales y definitivos, y la recuperación de las notificaciones que quedaron en PROCESANDO.

```mermaid
sequenceDiagram
    autonumber
    participant P as Productor (MS3 / MS8)
    participant N as Notificaciones
    participant DB as Base de notificaciones
    participant T as Tarea de envío
    participant U as Gestión de usuarios (MS6)
    participant S as SMTP

    rect rgb(235, 242, 250)
    Note over P,DB: 1. Recepción (sin enviar el correo)
    P->>N: POST /internal/notificaciones (X-Service-Token, aviso)
    alt token ausente o incorrecto
        N-->>P: 403 ACCESO_DENEGADO
    else aviso inválido o versión no soportada
        N-->>P: 400 VALIDACION / ESQUEMA_NO_SOPORTADO
    else clave (acontecimiento + tipo + destinatario) ya registrada
        N->>DB: busca por clave_deduplicacion
        N-->>P: 202 {id, estado, duplicado: true}
    else aviso nuevo
        N->>DB: INSERT notificación PENDIENTE (restricción única por clave)
        N-->>P: 202 {id, PENDIENTE, duplicado: false}
    end
    end

    rect rgb(238, 247, 238)
    Note over T,S: 2. Envío asíncrono (cada intervalo)
    T->>DB: PENDIENTE con próximo intento vencido (lote)
    T->>DB: UPDATE a PROCESANDO WHERE id = ? AND estado = PENDIENTE
    alt 0 filas: otra instancia la tomó
        T->>T: la saltea
    else 1 fila: la toma esta instancia
        T->>U: GET /internal/usuarios/{id}/contacto (timeout + circuit breaker)
        alt usuario inexistente o sin correo
            T->>DB: ERROR_FINAL con último error
        else MS6 caído, timeout o circuito abierto
            T->>DB: PENDIENTE, intentos + 1, próximo intento con espera creciente (o ERROR_FINAL si se agotaron)
        else correo obtenido
            T->>S: envía el correo según la plantilla del tipo (timeout + circuit breaker)
            alt SMTP acepta
                T->>DB: ENVIADA con fecha de envío
            else SMTP falla
                T->>DB: PENDIENTE, intentos + 1, próximo intento (o ERROR_FINAL si se agotaron)
            end
        end
    end
    end

    rect rgb(250, 240, 235)
    Note over T,DB: 3. Recuperación (cada intervalo)
    T->>DB: PROCESANDO con vencimiento pasado a PENDIENTE (intentos + 1) o a ERROR_FINAL si los agotó
    end
```

## 2. Diagrama de estados de una notificación

```mermaid
stateDiagram-v2
    [*] --> PENDIENTE: aviso recibido (POST 202)
    PENDIENTE --> PROCESANDO: la tarea de envío la toma (UPDATE condicional)
    PROCESANDO --> ENVIADA: SMTP acepta el correo
    PROCESANDO --> PENDIENTE: fallo temporal con intentos restantes
    PROCESANDO --> ERROR_FINAL: destinatario inexistente o sin correo
    PROCESANDO --> ERROR_FINAL: fallo temporal y se agotaron los intentos
    PROCESANDO --> PENDIENTE: venció en PROCESANDO (recuperación)
    PROCESANDO --> ERROR_FINAL: venció en PROCESANDO sin intentos restantes
    ENVIADA --> [*]
    ERROR_FINAL --> [*]
```

## 3. Modelo de datos

Una sola tabla. El destinatario se guarda solo por ID (el correo se pide a Gestión de usuarios al enviar) y no hay claves foráneas a otros servicios.

```mermaid
erDiagram
    NOTIFICACION {
        UUID id PK
        varchar clave_deduplicacion UK "acontecimiento | tipo | destinatario"
        varchar tipo "COMPRA_CONFIRMADA, EVENTO_CANCELADO, ..."
        varchar estado "PENDIENTE, PROCESANDO, ENVIADA, ERROR_FINAL"
        varchar usuario_id "destinatario (MS6), sin FK"
        varchar recurso_id "compra, evento, devolución o reintegro"
        varchar correlacion_id
        int version_esquema
        timestamp ocurrido_en
        text datos "JSON del aviso"
        int intentos
        timestamp proximo_intento
        timestamp vence_procesamiento
        varchar ultimo_error
        timestamp creada_en
        timestamp enviada_en
    }
```
