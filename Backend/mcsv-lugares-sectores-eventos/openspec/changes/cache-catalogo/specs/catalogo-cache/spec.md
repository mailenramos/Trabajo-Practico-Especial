## ADDED Requirements

### Requirement: Cachear la búsqueda pública del catálogo
MS1 SHALL usar Spring Cache y Caffeine únicamente para `GET /eventos`, con TTL configurable de default `30s` y tamaño máximo configurable de default `1000` entradas. La segunda búsqueda pública equivalente dentro del TTL SHALL reutilizar los datos sin releer MongoDB.

#### Scenario: Reutilizar búsqueda pública
- **GIVEN** una búsqueda válida ya almacenada en la caché local
- **WHEN** se repite con los mismos filtros, orden y paginación antes de vencer
- **THEN** responde con el mismo resultado público sin volver a leer MongoDB

#### Scenario: Vencer una entrada por TTL
- **GIVEN** una búsqueda almacenada en la caché local
- **WHEN** transcurre el TTL configurado desde que se almacenó y se repite
- **THEN** vuelve a leer MongoDB y actualiza la entrada

#### Scenario: Respetar el tamaño configurado
- **GIVEN** una caché de búsqueda con tamaño máximo configurado
- **WHEN** se insertan más entradas que dicho máximo
- **THEN** la caché permanece limitada y una entrada desalojada se vuelve a leer de MongoDB al consultarla

### Requirement: Aislar claves y conservar validaciones
Las claves SHALL distinguir todos los filtros, texto, orden, dirección y paginación efectivos, incluido `organizadorId`. SHALL NOT cachearse errores ni respuestas que dependan de identidad. Las validaciones y respuestas de error SHALL conservar su comportamiento.

#### Scenario: Distinguir parámetros de búsqueda
- **GIVEN** una búsqueda pública almacenada
- **WHEN** otra búsqueda difiere en un filtro, texto, orden, dirección o paginación relevante
- **THEN** no reutiliza incorrectamente el resultado anterior

#### Scenario: No ocultar parámetros inválidos con una entrada cacheada
- **GIVEN** una búsqueda válida previamente cacheada
- **WHEN** se solicita otra búsqueda con parámetros inválidos
- **THEN** responde `400 Bad Request` y no reutiliza el resultado válido

### Requirement: Mantener consultas internas y disponibilidad sin caché
MS1 SHALL NOT cachear consultas internas, disponibilidad, reservas, pagos, validaciones QR ni respuestas que dependan de identidad. Las rutas internas SHALL leer el estado actual, incluido `requiereFila` y la autorización del personal.

#### Scenario: Consultas internas reflejan el dato actual
- **GIVEN** se modifican `requiereFila` o la autorización de personal de un evento
- **WHEN** se consultan sus respectivos endpoints internos
- **THEN** reflejan inmediatamente los valores persistidos sin usar la caché de búsqueda

### Requirement: Invalidar la búsqueda al cambiar datos públicos
MS1 SHALL centralizar la invalidación local en un componente compartido. Tras una escritura exitosa que cambie el catálogo SHALL invalidar la búsqueda, después del commit cuando exista una transacción. SHALL incluir publicación, modificación, suspensión, reactivación, cancelación, finalización y toda otra escritura que cambie datos visibles.

#### Scenario: Invalidar al publicar
- **GIVEN** una búsqueda cacheada y un evento en borrador listo para publicar
- **WHEN** la publicación se confirma
- **THEN** la próxima búsqueda relee la base y refleja el evento publicado

#### Scenario: Invalidar al modificar datos visibles
- **GIVEN** una búsqueda cacheada
- **WHEN** una escritura exitosa modifica datos visibles de la búsqueda
- **THEN** la siguiente consulta relee la base y refleja los datos modificados

#### Scenario: Invalidar al suspender o reactivar la venta
- **GIVEN** una búsqueda cacheada
- **WHEN** se confirma una suspensión o reactivación de venta
- **THEN** la siguiente búsqueda relee la base y refleja el nuevo valor de venta suspendida

#### Scenario: Invalidar al cancelar
- **GIVEN** una búsqueda cacheada que incluye un evento publicado
- **WHEN** se confirma la cancelación
- **THEN** la siguiente búsqueda relee la base y deja de incluirlo

#### Scenario: Invalidar al finalizar
- **GIVEN** una búsqueda cacheada que incluye un evento publicado
- **WHEN** se confirma su finalización
- **THEN** la siguiente búsqueda relee la base y deja de incluirlo

#### Scenario: Invalidar ante cambio de agotamiento
- **GIVEN** una búsqueda cacheada
- **WHEN** un callback cambia el estado entre publicado y agotado
- **THEN** la siguiente búsqueda relee la base y refleja el estado confirmado

#### Scenario: No invalidar una transacción revertida
- **GIVEN** una publicación o cancelación dentro de una transacción que falla
- **WHEN** la transacción se revierte
- **THEN** no se trata la escritura como confirmada ni se invalida por ese cambio
