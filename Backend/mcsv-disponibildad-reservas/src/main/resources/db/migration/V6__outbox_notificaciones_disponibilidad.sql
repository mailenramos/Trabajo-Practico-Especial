CREATE TABLE `notificaciones_disponibilidad_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `evento_id` VARCHAR(255) NOT NULL,
  `agotado` BIT(1) NOT NULL,
  `creada_en` DATETIME(6) NOT NULL,
  `estado` VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE',
  `intentos` INT NOT NULL DEFAULT 0,
  `proximo_intento_en` DATETIME(6) NOT NULL,
  `lease_hasta` DATETIME(6) DEFAULT NULL,
  `token_lease` VARCHAR(36) DEFAULT NULL,
  `enviada_en` DATETIME(6) DEFAULT NULL,
  `ultimo_error` VARCHAR(2000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_outbox_pendientes` (`estado`, `proximo_intento_en`, `creada_en`, `id`),
  KEY `ix_outbox_evento_orden` (`evento_id`, `id`, `estado`),
  CONSTRAINT `ck_outbox_intentos_no_negativos` CHECK (`intentos` >= 0),
  CONSTRAINT `ck_outbox_estado` CHECK (`estado` IN ('PENDIENTE', 'PROCESANDO', 'ENVIADA')),
  CONSTRAINT `ck_outbox_estado_envio` CHECK (
    (`estado` = 'PENDIENTE' AND `lease_hasta` IS NULL AND `token_lease` IS NULL AND `enviada_en` IS NULL)
    OR (`estado` = 'PROCESANDO' AND `lease_hasta` IS NOT NULL AND `token_lease` IS NOT NULL AND `enviada_en` IS NULL)
    OR (`estado` = 'ENVIADA' AND `lease_hasta` IS NULL AND `token_lease` IS NULL AND `enviada_en` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
