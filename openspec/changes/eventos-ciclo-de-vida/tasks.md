# Tasks

## 1. (A) Trabajo independiente de otros servicios

- [x] 1.1 Crear el documento Mongo `Evento` con ID String/ObjectId hexadecimal, ciudad y sectores copiados, precio decimal `BigDecimal`/Decimal128, `fechaHora`, estados, `ventaSuspendida`, `limiteEntradasPorCliente` y versión; verificar serialización con pruebas unitarias.
- [x] 1.2 Agregar repositorio Mongo, consultas e índices para organizador, estado, ciudad y fecha/hora; verificar consultas con Testcontainers Mongo.
- [x] 1.3 Implementar lectura local de lugares/sectores para validar la creación y guardar copias del nombre y configuración de cada sector; verificar evento válido y lugar inexistente con pruebas.
- [x] 1.4 Implementar creación de evento borrador con autenticación `X-User-Id`/`X-User-Role` y validación de campos/fecha futura; verificar respuestas `201`, `400`, `401`, `403` y `404`.
- [x] 1.5 Implementar límite por cliente configurable durante borrador, entero >= 1 y default 4; verificar valor omitido, valores inválidos y rechazo `409` al cambiarlo después de publicar.
- [x] 1.6 Implementar asignación de precios >= 0 solo a sectores del lugar y por el organizador dueño en borrador, guardando el nombre copiado del sector; verificar negativos, sector ajeno, permisos y precio inmutable después de publicar.
- [x] 1.7 Implementar cálculo decimal de `precioMinimo` a partir de todos los sectores al publicar; verificar cero, precios decimales y no uso de `double`.
- [x] 1.8 Implementar estados formales, `ventaSuspendida` independiente y escritura condicional/versionada; verificar transiciones locales permitidas y conflictos concurrentes sin sobrescribir cancelación/finalización.
- [x] 1.9 Configurar formato común de error `timestamp/status/error/mensaje/campos` y puerto por defecto 8081 con sobrescritura por `PORT`; verificar JSON de errores y configuración efectiva.
- [x] 1.10 Completar pruebas unitarias y de integración Mongo de las reglas locales y documentar campos/errores propios del API; verificar la suite sin depender de Reservas ni Compras.

## 2. (B) Reservas — contratos confirmados, listo para implementar

- [x] 2.1 Crear interfaz `ReservasClient` y adaptador HTTP con `reservas.base-url` (default `http://localhost:8080`), timeouts configurables y manejo JSON; verificar rutas, cuerpos y respuestas con un cliente simulado.
- [x] 2.2 Construir el PUT de inicialización con `limiteEntradasPorCliente`, sectores y asientos numerados derivados de filas × asientosPorFila, enviando `fila` como entero; verificar capacidad total, asientos y no persistencia de asientos con pruebas del cliente simulado.
- [x] 2.3 Integrar publicación: cambiar a publicado solo tras respuesta `204`; ante timeout, 5xx o `409`, mantener borrador y responder `503`; verificar reintento idempotente que recibe `204` sin duplicar disponibilidad mediante cliente simulado.
- [x] 2.4 Integrar suspensión, reactivación, cancelación y finalización llamando a Reservas antes de mutar el estado local; ante fallo, mantenerlo y responder `503`; verificar éxito, fallos y repetición idempotente de estado `204` con cliente simulado.
- [x] 2.5 Implementar callback directo `PUT /eventos/{eventoId}/disponibilidad`, sin headers de usuario ni ruta del Gateway; verificar `204` para cambios/duplicados/avisos tardíos cancelado-finalizado, `404` inexistente y `409` borrador.
- [x] 2.6 Documentar el contrato de consulta `GET /disponibilidad/eventos/{eventoId}` para el futuro detalle US-3.3 sin implementarlo en este cambio; verificar que se conserva la decisión de responder disponibilidad `null` si Reservas falla.
- [x] 2.7 Probar integración de estados y concurrencia con cliente HTTP simulado, incluidos callback de agotado concurrente con cancelación y fallos/timeout de Reservas; verificar que no se publica ni cambia estado local sin confirmación remota.

## 3. (B) Compras — pendiente de confirmar con el equipo

- [ ] 3.1 Confirmar ruta/cuerpo/respuestas de `GET /internal/eventos/{eventoId}`, contrato de `POST /eventos-cancelados`, puerto de Compras e idempotencia del aviso; registrar el acuerdo antes de implementar los endpoints/clientes.
- [ ] 3.2 Crear interfaz `ComprasClient` y adaptador HTTP con `compras.base-url` y timeouts configurables; verificar serialización decimal/fecha y respuestas con un cliente simulado.
- [ ] 3.3 Implementar la consulta interna del evento para Compras con cualquier estado, `fechaInicio` ISO-8601 UTC y sectores con nombre/precio; mantenerla fuera del Gateway y sin headers de usuario; verificar `200` y `404`.
- [ ] 3.4 Persistir en el evento cancelado el aviso pendiente a Compras y reintentar cuando no responda, sin revertir la cancelación; verificar fallo, reenvío y no duplicación con cliente simulado.
- [ ] 3.5 Ejecutar pruebas de integración del contrato de Compras con cliente simulado, incluyendo fecha UTC, precios decimales y aviso de cancelación idempotente; verificar que errores remotos no alteran el estado cancelado.
