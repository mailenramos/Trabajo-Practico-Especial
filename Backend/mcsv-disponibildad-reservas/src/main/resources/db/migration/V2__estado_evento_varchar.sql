ALTER TABLE `eventos_disponibilidad`
  MODIFY COLUMN `estado_evento` VARCHAR(32) NOT NULL;

ALTER TABLE `eventos_disponibilidad`
  ADD CONSTRAINT `ck_evento_estado`
  CHECK (CAST(`estado_evento` AS BINARY) IN (
    CAST('PENDIENTE_PUBLICACION' AS BINARY),
    CAST('PUBLICADO' AS BINARY),
    CAST('AGOTADO' AS BINARY),
    CAST('CANCELADO' AS BINARY),
    CAST('FINALIZADO' AS BINARY)
  ));
