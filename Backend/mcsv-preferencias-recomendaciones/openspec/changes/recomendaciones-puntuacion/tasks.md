# Tasks

## 1. Integrar clientes HTTP

- [x] 1.1 Configurar cliente RestClient de MS1 con URL y timeouts; recorrer páginas `page` cero-based con `size=200`. Verificar paginación completa, parámetros y manejo de errores con `MockRestServiceServer`.
- [x] 1.2 Configurar cliente RestClient de Compras para historial y co-compras, enviando `X-Service-Token` en cada llamada y procesando los campos opcionales. Verificar rutas, payloads, máximo de 20 `eventoId` distintos en orden de compras confirmadas más recientes y máximo de cinco consultas de co-compra con `MockRestServiceServer`.
- [x] 1.3 Agregar propiedades `EVENTOS_BASE_URL`, `COMPRAS_BASE_URL`, `SERVICIO_TOKEN` y timeouts de 2 s/5 s, con defaults host indicados en el diseño. Verificar valores cargados y que no exista un token útil predeterminado.

## 2. Calcular recomendaciones

- [x] 2.1 Implementar el cálculo con preferencias existentes e historial/co-compras opcionales, normalizando comparaciones y aplicando cada peso una sola vez. Verificar cada peso, suma de coincidencias, historial sin compras, preferencias ausentes y fallback completo a preferencias ante cualquier error de Compras.
- [x] 2.2 Filtrar por estado `publicado`/`agotado` sin distinguir mayúsculas, fecha futura y puntuación positiva; ordenar por puntuación, fecha e ID y limitar a 20. Verificar eventos pasados y estados excluidos, comparación de mayúsculas, empate determinista, límite 20 y lista vacía con reloj controlado.

## 3. Exponer recomendaciones autenticadas

- [ ] 3.1 Exponer `GET /recomendaciones/me`, aplicar la identidad existente y documentar contrato y respuestas en OpenAPI. Verificar con MVC `401` si falta cualquiera de los headers, `403` sin `CLIENTE`, `200` autorizado y `503` si MS1 falla.
- [ ] 3.2 Manejar fallos de Compras sin interrumpir el endpoint y conservar el error estándar `503` únicamente para fallos de MS1. Verificar que la respuesta autorizada mantiene `200` y usa solo preferencias cuando Compras falla.

## 4. Actualizar ejecución y uso

- [ ] 4.1 Agregar variables de integración a `docker-compose.yml` con defaults Docker y sin credenciales incorporadas. Verificar `docker compose config --quiet` y las URLs/variables resueltas.
- [ ] 4.2 Actualizar README con endpoint, autorización, fuentes, variables y pesos; documentar las simplificaciones (máximo 20, cinco eventos para co-compras, cantidades sin ponderar, fallback de Compras y eventos comprados no excluidos). Verificar que valores y comportamiento coinciden con la spec y Compose.
- [ ] 4.3 Agregar `GET /recomendaciones/me` a la colección Postman con base URL y headers de identidad configurables. Verificar que el JSON se importa y la solicitud apunta a la ruta especificada.

## 5. Validar el cambio

- [ ] 5.1 Ejecutar `mvnw.cmd test` con Docker disponible y verificar que pasan pruebas MVC, unitarias y de clientes HTTP.
- [ ] 5.2 Ejecutar `openspec validate recomendaciones-puntuacion --strict` desde MS8 y corregir cualquier error o advertencia.
