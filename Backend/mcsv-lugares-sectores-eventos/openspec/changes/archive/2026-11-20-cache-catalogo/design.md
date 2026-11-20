# Diseño: caché local del catálogo

## Alcance y configuración

Usar Spring Cache con Caffeine exclusivamente para `GET /eventos`. `catalogo.cache.ttl` tendrá default `30s` y expiración desde la escritura; `catalogo.cache.maximo-entradas` tendrá default `1000`. Ambos valores serán configurables, positivos y no sensibles. El detalle conserva exactamente su comportamiento actual y queda fuera de esta caché.

## Búsqueda y claves

La búsqueda devuelve exclusivamente `publicado` y `agotado` y no depende de la identidad. Validar y normalizar sus parámetros antes de consultar la caché; la clave incluye todos los filtros efectivos, texto, orden, dirección y paginación, incluido `organizadorId` cuando se usa como filtro. No confundir una entrada inválida con una válida. Se cachea el resultado paginado público, incluidos sus metadatos; no se cachean errores.

Una misma entrada solo se comparte cuando el resultado es idéntico para todos los usuarios. El criterio `organizadorId` es parte de la consulta, no una identidad implícita. Separar el acceso cacheable en un componente invocado a través del proxy de Spring, evitando autoinvocación que saltee Spring Cache. Nunca cachear `/internal/**`, disponibilidad, reservas, pagos, validaciones QR ni respuestas que dependan de identidad; `requiereFila` se sigue leyendo directamente en cada consulta interna.

## Invalidación

Un único componente `CatalogoCacheInvalidador` concentra la invalidación de la caché de búsqueda completa, ya que las escrituras pueden afectar filtros, páginas y totales. Conectarlo a publicación, suspensión, reactivación, cancelación, finalización automática y callback que cambia entre publicado y agotado. Las futuras escrituras que cambien datos visibles en la búsqueda, como `modificar-evento`, deberán invocarlo también; no se agregan endpoints nuevos.

Publicación y cancelación persisten dentro de transacciones MongoDB junto a outbox: invalidar después del commit, nunca por un rollback ni antes de que el dato confirmado sea visible. Para escrituras sin transacción, invalidar después de guardar exitosamente.

Cada instancia mantiene su propia caché; no hay invalidación distribuida ni coordinación de cargas concurrentes. Una consulta concurrente puede volver a guardar un resultado anterior tras una invalidación y otras instancias no reciben esa invalidación. Los datos pueden quedar desactualizados hasta el vencimiento del TTL configurado desde su almacenamiento, 30 segundos por defecto.

## Verificación

Usar pruebas con contador/espía de lecturas Mongo para asegurar que la segunda búsqueda equivalente no relee la base. Probar expiración sin sleeps frágiles mediante tiempo controlable, claves de todos los parámetros, errores y límite de tamaño. Cubrir invalidación para cada operación, rollback y consultas internas actuales. Utilizar MongoDB de Testcontainers para integración, nunca una instancia local compartida.
