## ADDED Requirements

### Requirement: Consultar eventos publicados para recomendaciones
MS1 SHALL ofrecer `GET /internal/eventos-publicados` para Preferencias y recomendaciones (MS8), sin headers de usuario y sin publicar la ruta por el API Gateway. SHALL devolver solo eventos en estado `publicado` o `agotado`, sin filtrar por fecha. Cada evento SHALL incluir `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` del lugar, `fechaHora` en ISO-8601 UTC y `estado`. MS8 es responsable de excluir eventos finalizados, cancelados y con fecha pasada.

#### Scenario: MS8 consulta eventos sin identidad de usuario
- **GIVEN** existen eventos en distintos estados
- **WHEN** MS8 consulta `GET /internal/eventos-publicados` sin headers de usuario
- **THEN** responde `200 OK` con únicamente eventos `publicado` o `agotado` y los campos acordados, sin requerir autenticación de usuario

### Requirement: Ordenar y paginar la consulta interna de eventos para MS8
La consulta SHALL ordenar por `fechaHora` ascendente y por ID del evento ascendente como desempate. SHALL usar `page` cero-based, default `0`, y `size` default `100` con máximo `200`. Los valores inválidos SHALL responder `400 Bad Request` con el formato de error estándar.

#### Scenario: Orden estable para paginar
- **GIVEN** existen eventos `publicado` o `agotado` con fechas iguales
- **WHEN** MS8 consulta páginas consecutivas
- **THEN** los resultados se ordenan por `fechaHora` ascendente y, a igual fecha, por ID del evento ascendente

#### Scenario: Paginación de eventos para MS8
- **GIVEN** existen más de 100 eventos `publicado` o `agotado`
- **WHEN** MS8 consulta sin `page` ni `size`
- **THEN** se usa `page=0` y `size=100`, y la respuesta incluye `contenido`, `pagina`, `tamano`, `totalElementos` y `totalPaginas`

#### Scenario: Rechazar tamaño de página mayor que el máximo
- **GIVEN** MS8 solicita eventos con `size` mayor que 200
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Rechazar tamaño de página menor que uno
- **GIVEN** MS8 solicita eventos con `size` menor que 1
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

#### Scenario: Rechazar número de página negativo
- **GIVEN** MS8 solicita eventos con `page` negativo
- **WHEN** se procesa la consulta
- **THEN** responde `400 Bad Request` con el formato de error estándar

### Requirement: Notificar publicaciones de eventos a Preferencias mediante outbox
Después de que Reservas confirme la inicialización, MS1 SHALL guardar en una transacción MongoDB el evento publicado y un aviso outbox tipo `PUBLICACION` con ID `publicacion-{eventoId}`. Tras el commit, SHALL intentar entregarlo fuera de la transacción por el dispatcher general a `POST {PREFERENCIAS_BASE_URL}/internal/preferencias/eventos-publicados`. Los fallos no SHALL revertir la publicación ni alterar su respuesta; los reintentos SHALL ser asíncronos por el dispatcher.

#### Scenario: Entregar el aviso de publicación por el dispatcher
- **GIVEN** un evento en borrador listo para publicar y Reservas confirma la inicialización
- **WHEN** se confirma la transacción local y el dispatcher entrega el aviso
- **THEN** envía `X-Aviso-Id: publicacion-{eventoId}` y un payload con `eventoId`, `nombre`, `tipo`, `participantes`, `ciudad` y `fechaHora` ISO-8601 UTC al destino configurado

#### Scenario: Publicar un evento sin participantes
- **GIVEN** un evento listo para publicar sin participantes
- **WHEN** Reservas confirma la inicialización y MS1 registra el aviso `PUBLICACION`
- **THEN** el payload contiene `participantes` como una lista vacía y no como `null`

#### Scenario: Preferencias no está disponible al publicar
- **GIVEN** Reservas confirmó la inicialización y la transacción local guardó el evento y el aviso
- **WHEN** Preferencias falla o no responde al intento de entrega
- **THEN** el evento permanece publicado, el administrador conserva la respuesta exitosa y el aviso queda sujeto a los reintentos del dispatcher general

## MODIFIED Requirements

### Requirement: Persistir avisos outbox atómicamente
Cuando una mutación origine un aviso, MS1 SHALL guardar el cambio local y un aviso en la colección MongoDB `outbox` en la misma transacción. Cada aviso SHALL incluir ID estable, tipo, `eventoId`, destino, payload, estado, intentos, próximo intento, último error y fechas. Los estados SHALL ser `PENDIENTE`, `PROCESANDO`, `ENTREGADO` o `ERROR_FINAL`. Los productores cubiertos SHALL ser la cancelación a Compras y la publicación a Preferencias.

#### Scenario: Mantener el evento y el aviso atómicos
- **GIVEN** una cancelación que escribe el cambio del evento y el aviso outbox en una transacción
- **WHEN** falla una de las escrituras antes del commit
- **THEN** no queda persistido ni el cambio local del evento ni un aviso huérfano

#### Scenario: Mantener la publicación y su aviso atómicos
- **GIVEN** una publicación tras la confirmación de Reservas
- **WHEN** falla una de las escrituras del evento o del aviso outbox antes del commit
- **THEN** no queda persistido ni el cambio local del evento ni un aviso huérfano
