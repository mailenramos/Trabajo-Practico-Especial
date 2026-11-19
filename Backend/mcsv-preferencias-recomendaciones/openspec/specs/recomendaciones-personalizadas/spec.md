# recomendaciones-personalizadas Specification

## Purpose
Permite que un cliente consulte eventos futuros ordenados por su afinidad con preferencias y señales opcionales de compras, sin convertir fallos de Compras en una interrupción del servicio.

## Requirements

### Requirement: Proteger las recomendaciones con el rol CLIENTE
`GET /recomendaciones/me` SHALL requerir `X-User-Id` y `X-User-Roles`, y que la lista de roles incluya `CLIENTE`. Los roles SHALL separarse por comas, recortarse y compararse sin distinguir mayúsculas.

#### Scenario: Falta cualquiera de los headers
- **WHEN** la solicitud omite `X-User-Id` o `X-User-Roles`
- **THEN** el servicio responde `401 Unauthorized`

#### Scenario: El usuario no tiene el rol CLIENTE
- **GIVEN** la solicitud incluye ambos headers y los roles no incluyen `CLIENTE`
- **WHEN** el usuario solicita `GET /recomendaciones/me`
- **THEN** el servicio responde `403 Forbidden`

#### Scenario: Cliente autenticado consulta recomendaciones
- **GIVEN** ambos headers identifican a un usuario cuyo rol incluye `CLIENTE`
- **WHEN** solicita `GET /recomendaciones/me`
- **THEN** el servicio procesa la solicitud para el `X-User-Id` autenticado

### Requirement: Obtener el catálogo completo de eventos de MS1
El servicio SHALL consultar todas las páginas de `{EVENTOS_BASE_URL}/internal/eventos-publicados`, usando páginas cero-based y un tamaño de hasta 200. Si MS1 falla, no responde o devuelve error, el endpoint SHALL responder `503 Service Unavailable`.

#### Scenario: Recorrer todas las páginas
- **GIVEN** MS1 devuelve más de una página de eventos
- **WHEN** un cliente solicita recomendaciones
- **THEN** el servicio considera el contenido de todas las páginas antes de seleccionar resultados

#### Scenario: MS1 no está disponible
- **GIVEN** MS1 no responde o devuelve una respuesta de error
- **WHEN** un cliente solicita recomendaciones
- **THEN** el servicio responde `503 Service Unavailable`

### Requirement: Consultar el historial de compras del cliente
El servicio SHALL consultar `GET {COMPRAS_BASE_URL}/internal/usuarios/{usuarioId}/historial-preferencias` con `X-Service-Token`. SHALL interpretar `tipos` como tipos comprados y tratar `eventosComprados` omitido como una lista vacía. Cuando se informa, `eventosComprados` SHALL contener los `eventoId` de compras confirmadas, sin repetirlos, del más reciente al más antiguo, con un máximo de 20.

#### Scenario: Historial sin eventosComprados o sin compras
- **GIVEN** el historial no incluye `eventosComprados` o `tipos` está vacío
- **WHEN** el servicio obtiene señales de Compras
- **THEN** trata los campos ausentes o vacíos como listas vacías y no consulta co-compras si no hay eventos comprados

#### Scenario: Historial limita y ordena eventos comprados
- **GIVEN** el historial incluye más de un evento comprado
- **WHEN** MS8 recibe `eventosComprados`
- **THEN** cada `eventoId` es distinto, corresponde a compras confirmadas y la lista está ordenada de la compra más reciente a la más antigua, con un máximo de 20 elementos

### Requirement: Consultar co-compras con degradación ante fallos de Compras
El servicio SHALL consultar `GET {COMPRAS_BASE_URL}/internal/eventos/{eventoId}/co-compras` con `X-Service-Token` para no más de cinco IDs distintos de `eventosComprados`; cada respuesta aporta hasta diez IDs co-comprados. Si falla, no responde o devuelve error cualquiera de las consultas de Compras, el servicio SHALL descartar todas las señales de Compras de esa solicitud y continuar usando solo preferencias.

#### Scenario: Historial disponible y eventos comprados presentes
- **GIVEN** Compras devuelve `tipos` y `eventosComprados`
- **WHEN** el servicio obtiene señales para las recomendaciones
- **THEN** consulta co-compras para no más de cinco eventos comprados y usa el historial y sus resultados para puntuar

#### Scenario: Una consulta de Compras falla
- **GIVEN** falla, no responde o devuelve error cualquiera de las consultas de historial o co-compras
- **WHEN** el servicio calcula recomendaciones
- **THEN** omite todos los puntos de historial y co-compra y continúa con las preferencias, sin propagar el error de Compras

### Requirement: Puntuar coincidencias de preferencias y compras
El servicio SHALL sumar por evento `+5` si algún participante coincide con un artista favorito, `+3` por tipo favorito, `+2` por ciudad favorita, `+1` si el tipo aparece en el historial y `+2` si el evento aparece en alguna co-compra consultada. Las coincidencias SHALL recortarse y compararse sin distinguir mayúsculas; cada regla aporta puntos como máximo una vez por evento.

#### Scenario: Sumar todas las coincidencias aplicables
- **GIVEN** un evento coincide con un artista, tipo y ciudad favoritos, un tipo del historial y una co-compra
- **WHEN** el servicio calcula su puntuación
- **THEN** suma `13` puntos al evento

#### Scenario: Comparar texto normalizado y aplicar cada regla una vez
- **GIVEN** varias coincidencias de participantes o IDs de co-compra representan la misma regla para un evento
- **WHEN** el servicio calcula la puntuación con textos que difieren solo en espacios exteriores o mayúsculas
- **THEN** reconoce la coincidencia y suma los puntos de cada regla aplicable una sola vez

#### Scenario: No hay preferencias guardadas
- **GIVEN** el usuario no tiene documento de preferencias
- **WHEN** el servicio calcula recomendaciones
- **THEN** trata artistas, tipos y ciudades favoritos como listas vacías

### Requirement: Filtrar y devolver recomendaciones ordenadas
El servicio SHALL considerar solo eventos con estado `publicado` o `agotado`, comparado sin distinguir mayúsculas, y `fechaHora` posterior al momento de consulta. SHALL devolver solo puntuaciones mayores que cero, limitar la respuesta a 20 eventos y ordenar por puntuación descendente, `fechaHora` ascendente y `eventoId` ascendente. Cada elemento incluye `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad`, `fechaHora`, `estado` y `puntuacion`.

#### Scenario: Excluir eventos no vigentes y sin afinidad
- **GIVEN** el catálogo contiene eventos pasados, cancelados, finalizados o con puntuación cero
- **WHEN** el servicio genera recomendaciones
- **THEN** no incluye esos eventos en la respuesta

#### Scenario: Comparar estado sin distinguir mayúsculas
- **GIVEN** un evento futuro tiene estado `PUBLICADO` o `Agotado`
- **WHEN** el servicio genera recomendaciones
- **THEN** evalúa el evento como publicado o agotado, respectivamente

#### Scenario: Ordenar y limitar resultados
- **GIVEN** hay más de 20 eventos futuros publicados o agotados con puntuación positiva
- **WHEN** el servicio responde
- **THEN** devuelve como máximo 20 eventos ordenados por puntuación descendente, fecha ascendente e ID ascendente

#### Scenario: No hay recomendaciones positivas
- **GIVEN** ningún evento futuro publicado o agotado tiene puntuación positiva
- **WHEN** el cliente solicita recomendaciones
- **THEN** el servicio responde `200 OK` con una lista vacía
