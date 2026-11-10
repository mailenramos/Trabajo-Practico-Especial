## 1. Modelo de datos

- [x] 1.1 Entidad CancelacionEvento (eventoId clave primaria, motivo, canceladoEn, recibidaEn, estado EN_PROCESO/PROCESADA, tomadaEn, entradasMarcadas, ultimoError) y su repositorio con retoma condicional. Verificar con pruebas de repositorio: el mismo eventoId no se inserta dos veces y solo un pedido retoma una cancelación vencida
- [x] 1.2 Entidad EntradaDeReintegro (reintegroId, entradaId, devuelta) y su repositorio; búsqueda de compras confirmadas por eventoId y de reintegros por compra y origen. Verificar con pruebas de repositorio

## 2. Clientes de otros servicios

- [x] 2.1 EntradasCliente: marcar las entradas de un evento (POST /internal/eventos/{idEvento}/entradas/reintegro) leyendo entradasMarcadas, como provisorio. Doble de prueba repetible (la segunda vez responde 0). Verificar con una prueba contra el servidor de prueba

- [x] 2.2 Estado del evento en minúsculas (confirmado por Eventos: borrador, publicado, agotado, cancelado, finalizado): revisar que la devolución reconozca "cancelado" (DevolucionService ya compara sin distinguir mayúsculas) y que el doble de Eventos solo devuelva esos valores. Verificar con una prueba de que la devolución de una entrada de un evento "cancelado" responde 409 EVENTO_CANCELADO

## 3. Recepción del aviso (E-1)

- [x] 3.1 Endpoint POST /eventos-cancelados: 202 aviso nuevo o en proceso, 200 ya procesado, 400 sin eventoId; retoma de una cancelación EN_PROCESO vencida (compras.cancelacion.plazo-en-proceso, 5 min). Procesamiento en el TaskExecutor, sincrónico en las pruebas. Verificar con pruebas de cada respuesta y de que un aviso repetido no inicia otro procesamiento

## 4. Procesamiento (E-1)

- [x] 4.1 Servicio de cancelación: marcación de Entradas (informativa) y, por compra confirmada, cierre de solicitudes pendientes, reintegro único por compra (refundRequestId propio, reason "Evento cancelado", monto de las entradas vigentes) con sus entradas_de_reintegro, y entradas a devuelta con el reintegro exitoso; al final, PROCESADA. Verificar con pruebas de: compra con todas las entradas vigentes, compra con una entrada ya devuelta, compra sin entradas vigentes, compras pendientes y fallidas sin reintegro, solicitud pendiente cerrada, y aviso repetido (entradasMarcadas 0) sin otro reintegro
- [x] 4.2 Una falla en una compra no frena a las demás: reintegro fallido o sin respuesta queda registrado y el resto se procesa. Verificar con una prueba de dos compras donde la primera falla

## 5. Reintento (E-2)

- [x] 5.1 Rama de POST /reintegros/{id}/reintento para CANCELACION_EVENTO: reintenta y, si queda exitoso, pasa a devuelta las entradas incluidas que falten. 200 completo, 202 pendiente. Verificar con pruebas de reintento exitoso (entradas devueltas, mismo refundRequestId) y de reintento que vuelve a fallar

## 6. Documentación

- [x] 6.1 openapi.yaml: POST /eventos-cancelados con cuerpo, ejemplos y códigos 200/202/400, y el origen CANCELACION_EVENTO en reintegros. Verificar con DocumentacionOpenApiTest ampliada al endpoint nuevo
- [x] 6.2 docs/historias-ms3.md: E-1 con el reason "Evento cancelado", el monto de las entradas vigentes y el cierre de solicitudes pendientes; contrato de la marcación de Entradas; riesgo de la compra pendiente cobrada. Verificar releyendo que no quede una decisión contradictoria

## 7. Verificación final

- [x] 7.1 .\mvnw test en compras y openspec validate cancelacion-de-eventos sin errores

## Workflow follow-up

- Confirmar con Entradas que la marcación por evento es repetible.
- Plantear al grupo qué hacer con una compra pendiente ya cobrada cuando se cancela su evento.
- Archivar el cambio con /opsx:archive cuando esté revisado.
