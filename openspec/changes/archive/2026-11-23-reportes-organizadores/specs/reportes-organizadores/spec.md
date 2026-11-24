# Spec Delta

## Purpose

Permite que el organizador de un evento, o un administrador, consulte los reportes comerciales del evento (ventas, devoluciones, recaudación y ocupación), calculados al momento a partir de los datos de Compras y devoluciones y de consultas internas a Eventos y a Disponibilidad y reservas.

## ADDED Requirements

### Requirement: Acceso a los reportes según titularidad del evento
Los endpoints `GET /reportes/eventos/{eventoId}/ventas`, `/devoluciones`, `/recaudacion` y `/ocupacion` SHALL permitirse a un usuario con el rol administrador para cualquier evento, y a un usuario con el rol ORGANIZADOR solo cuando su `X-User-Id` coincide con el `organizadorId` que informa Eventos para ese evento.

#### Scenario: Organizador dueño del evento
- **WHEN** un usuario con rol ORGANIZADOR consulta un reporte de un evento cuyo `organizadorId` es su `X-User-Id`
- **THEN** el sistema responde 200 con el reporte

#### Scenario: Administrador
- **WHEN** un usuario con rol ADMIN consulta un reporte de un evento de cualquier organizador
- **THEN** el sistema responde 200 con el reporte

#### Scenario: Organizador ajeno
- **WHEN** un usuario con rol ORGANIZADOR consulta un reporte de un evento cuyo `organizadorId` es otro
- **THEN** el sistema responde 403 con el código `EVENTO_AJENO`
- **AND** la respuesta no incluye datos del evento

#### Scenario: Usuario sin rol de organizador ni administrador
- **WHEN** un usuario solo con rol CLIENTE consulta un reporte
- **THEN** el sistema responde 403 con el código `SIN_PERMISO_REPORTES`
- **AND** no consulta a Eventos

#### Scenario: Usuario no identificado
- **WHEN** se consulta un reporte sin el encabezado `X-User-Id`
- **THEN** el sistema responde 401 con el código `USUARIO_NO_IDENTIFICADO`

### Requirement: Evento inexistente o Eventos sin responder
Antes de calcular cualquier reporte el sistema SHALL consultar el evento a Eventos. Un evento inexistente SHALL responder 404 `EVENTO_NO_ENCONTRADO`; si Eventos no responde, SHALL responder 503 `SERVICIO_NO_DISPONIBLE` sin devolver datos parciales.

#### Scenario: Evento inexistente
- **WHEN** un administrador consulta un reporte de un evento que Eventos no encuentra
- **THEN** el sistema responde 404 con el código `EVENTO_NO_ENCONTRADO`

#### Scenario: Eventos caído
- **WHEN** se consulta un reporte y Eventos no responde
- **THEN** el sistema responde 503 con el código `SERVICIO_NO_DISPONIBLE`

### Requirement: Encabezado común de los reportes
Cada reporte SHALL incluir `eventoId`, `eventoNombre`, `estadoEvento` (según Eventos), `generadoEn` y `hayOperacionesPendientes`, que SHALL ser verdadero cuando el evento tiene compras PENDIENTES que ya tienen pago (cobradas sin confirmar), devoluciones en curso o reintegros sin resultado exitoso. Una compra PENDIENTE sin pago SHALL no contar como pendiente.

#### Scenario: Reporte sin pendientes
- **WHEN** se consulta un reporte de un evento sin compras PENDIENTES con pago, sin devoluciones en curso y sin reintegros pendientes o fallidos
- **THEN** `hayOperacionesPendientes` es falso

#### Scenario: Compra pendiente sin pago no es una operación pendiente
- **WHEN** el evento solo tiene, además de compras confirmadas, una compra PENDIENTE que todavía no tiene pago
- **THEN** `hayOperacionesPendientes` es falso

#### Scenario: Compra cobrada sin confirmar
- **WHEN** el evento tiene una compra PENDIENTE que ya tiene pago y cuya emisión no terminó
- **THEN** `hayOperacionesPendientes` es verdadero

#### Scenario: Reporte con pendientes
- **WHEN** se consulta un reporte de un evento con una devolución aprobada cuyo reintegro sigue pendiente
- **THEN** `hayOperacionesPendientes` es verdadero

### Requirement: Reporte de ventas
`GET /reportes/eventos/{eventoId}/ventas` SHALL informar, solo con compras CONFIRMADAS del evento, la cantidad de compras confirmadas, las entradas vendidas históricas y el importe vendido (suma de los precios unitarios guardados en cada compra), en total y agrupados por sector.

#### Scenario: Ventas por sector con precios históricos
- **WHEN** un evento tiene una compra confirmada de 2 entradas en Platea a 10000 y otra de 1 entrada en Campo a 5000, y luego cambia el precio de Platea
- **THEN** el reporte informa 2 compras confirmadas, 3 entradas vendidas y 25000 de importe vendido
- **AND** Platea informa 2 entradas y 20000, y Campo 1 entrada y 5000

#### Scenario: Compras no confirmadas no cuentan como ventas
- **WHEN** un evento tiene una compra PENDIENTE ya cobrada cuya emisión no terminó y una compra FALLIDA
- **THEN** ninguna de las dos suma compras, entradas ni importes vendidos
- **AND** la compra pendiente cobrada se informa aparte como compra con emisión incompleta, con su importe

#### Scenario: Las devoluciones no restan de las ventas históricas
- **WHEN** una entrada vendida se devuelve después
- **THEN** el reporte de ventas la sigue contando como vendida

#### Scenario: Entradas gratuitas
- **WHEN** una compra confirmada incluye una entrada con precio 0
- **THEN** la entrada cuenta en las entradas vendidas y aporta 0 al importe

#### Scenario: Evento sin ventas
- **WHEN** se consulta el reporte de un evento sin compras
- **THEN** el sistema responde 200 con cantidades e importes en 0 y la lista de sectores del evento con valores en 0

### Requirement: Reporte de devoluciones
`GET /reportes/eventos/{eventoId}/devoluciones` SHALL informar, por sector y total, las entradas vendidas históricas, las entradas devueltas (entradas distintas cuya baja se completó), separadas por origen `DEVOLUCION` y `CANCELACION_EVENTO`, las entradas netas (vendidas menos devueltas) y el importe de las entradas devueltas.

#### Scenario: Devolución del cliente completada
- **WHEN** una solicitud de devolución fue aprobada y la entrada quedó devuelta
- **THEN** la entrada cuenta como devuelta con origen DEVOLUCION en su sector y resta de las netas

#### Scenario: Entradas devueltas por cancelación del evento
- **WHEN** el evento se canceló y el reintegro de una compra quedó exitoso con sus entradas devueltas
- **THEN** esas entradas cuentan como devueltas con origen CANCELACION_EVENTO, separadas de las de origen DEVOLUCION

#### Scenario: Una entrada se cuenta una sola vez
- **WHEN** se consulta el reporte varias veces o una entrada aparece en más de un registro
- **THEN** cada entrada devuelta cuenta una única vez

#### Scenario: Devoluciones en curso aparte
- **WHEN** hay solicitudes de devolución PENDIENTES, o aprobadas cuya entrada todavía no quedó devuelta
- **THEN** el reporte las informa aparte como devoluciones en curso y no las resta de las netas

### Requirement: Reporte de recaudación
`GET /reportes/eventos/{eventoId}/recaudacion` SHALL informar, por sector y total, la recaudación bruta (precios históricos de las entradas de compras confirmadas), los reintegros exitosos (importes de reintegros EXITOSOS de esas compras) y la recaudación neta (bruta menos reintegros exitosos).

#### Scenario: Recaudación con una devolución reintegrada
- **WHEN** un evento recaudó 25000 brutos y una entrada de Platea de 10000 tuvo su reintegro EXITOSO
- **THEN** el reporte informa bruta 25000, reintegros exitosos 10000 y neta 15000
- **AND** Platea informa bruta 20000, reintegros 10000 y neta 10000

#### Scenario: Reintegro por cancelación repartido por sector
- **WHEN** el reintegro EXITOSO por cancelación de una compra incluye una entrada de Platea a 10000 y una de Campo a 5000
- **THEN** se descuentan 10000 en Platea y 5000 en Campo

#### Scenario: Reintegros sin resultado exitoso aparte
- **WHEN** un reintegro de una compra confirmada está PENDIENTE o FALLIDO
- **THEN** no se descuenta de la recaudación neta
- **AND** se informa aparte como pendiente de reconciliación, con su cantidad e importe

#### Scenario: Cobros de compras que no se confirmaron aparte
- **WHEN** una compra cobrada quedó FALLIDA (por ejemplo, venta rechazada) y tuvo su reintegro automático
- **THEN** su cobro y su reintegro no forman parte de la recaudación bruta ni neta
- **AND** se informan aparte como cobros y reintegros de compras no confirmadas

### Requirement: Reporte de ocupación
`GET /reportes/eventos/{eventoId}/ocupacion` SHALL informar, con los datos actuales de Disponibilidad y reservas, por sector la capacidad, los lugares vendidos, las reservas temporales y el porcentaje de ocupación (vendidos / capacidad × 100, con 2 decimales), y el total del evento (suma de vendidos / suma de capacidades × 100).

#### Scenario: Ocupación por sector y total
- **WHEN** Platea tiene capacidad 100, 40 vendidos y 5 reservados, y Campo capacidad 300, 60 vendidos y 0 reservados
- **THEN** Platea informa 40.00 % y Campo 20.00 %
- **AND** el total informa capacidad 400, 100 vendidos, 5 reservados y 25.00 %

#### Scenario: Las reservas temporales no cuentan como ocupación
- **WHEN** un sector tiene lugares reservados sin vender
- **THEN** esos lugares se informan como reservas temporales y no suman al porcentaje

#### Scenario: Sector con capacidad cero
- **WHEN** un sector informa capacidad 0
- **THEN** su porcentaje de ocupación es 0.00

#### Scenario: Evento sin inventario inicializado
- **WHEN** Disponibilidad y reservas responde que el evento no tiene inventario (por ejemplo, un borrador)
- **THEN** el sistema responde 200 con `inventarioInicializado` falso, sin sectores y con totales en 0

#### Scenario: Disponibilidad y reservas caído
- **WHEN** se consulta la ocupación y Disponibilidad y reservas no responde
- **THEN** el sistema responde 503 con el código `SERVICIO_NO_DISPONIBLE`

#### Scenario: Evento cancelado
- **WHEN** se consulta la ocupación de un evento cancelado
- **THEN** el reporte muestra el estado actual del inventario según Disponibilidad y reservas

### Requirement: Reportes de solo lectura y repetibles
Consultar un reporte SHALL no modificar compras, devoluciones, reintegros ni el inventario, y SHALL no guardar resultados precalculados; dos consultas seguidas sin operaciones intermedias SHALL devolver los mismos valores (salvo `generadoEn`).

#### Scenario: Consulta repetida
- **WHEN** un organizador consulta el mismo reporte dos veces sin operaciones intermedias
- **THEN** ambas respuestas tienen las mismas cantidades e importes

#### Scenario: Reintento tras una falla de Eventos
- **WHEN** una consulta respondió 503 porque Eventos no respondía y se repite cuando Eventos vuelve
- **THEN** la segunda consulta responde 200 con el reporte, sin efectos de la consulta fallida
