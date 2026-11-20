CREATE TABLE `filas_evento` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `evento_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `estado` VARCHAR(16) NOT NULL DEFAULT 'DESACTIVADA',
  `cupo` INT NOT NULL DEFAULT 10,
  `duracion_minutos` INT NOT NULL DEFAULT 5,
  `cancelado` BIT(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fila_evento_evento` (`evento_id`),
  CONSTRAINT `ck_fila_estado` CHECK (CAST(`estado` AS BINARY) IN ('ACTIVA', 'PAUSADA', 'DESACTIVADA')),
  CONSTRAINT `ck_fila_cupo_positivo` CHECK (`cupo` > 0),
  CONSTRAINT `ck_fila_duracion_positiva` CHECK (`duracion_minutos` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `turnos` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `fila_id` BIGINT NOT NULL,
  `usuario_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `estado` VARCHAR(16) NOT NULL DEFAULT 'ESPERANDO',
  `ingresado_en` DATETIME(6) NOT NULL,
  `habilitado_en` DATETIME(6) DEFAULT NULL,
  `expira_en` DATETIME(6) DEFAULT NULL,
  `solicitud_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `huella_solicitud` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  `usuario_activo` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
    GENERATED ALWAYS AS (CASE WHEN CAST(`estado` AS BINARY) IN ('ESPERANDO', 'HABILITADO')
      THEN `usuario_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_turno_usuario_solicitud` (`usuario_id`, `solicitud_id`),
  UNIQUE KEY `uk_turno_activo_fila_usuario` (`fila_id`, `usuario_activo`),
  KEY `ix_turno_fila_estado_id` (`fila_id`, `estado`, `id`),
  KEY `ix_turno_fila_estado_expira` (`fila_id`, `estado`, `expira_en`),
  KEY `ix_turno_estado_expira` (`estado`, `expira_en`),
  CONSTRAINT `fk_turno_fila` FOREIGN KEY (`fila_id`) REFERENCES `filas_evento` (`id`),
  CONSTRAINT `ck_turno_estado` CHECK (CAST(`estado` AS BINARY) IN ('ESPERANDO', 'HABILITADO', 'CONSUMIDO', 'VENCIDO')),
  CONSTRAINT `ck_turno_consumo` CHECK (
    (CAST(`estado` AS BINARY) = 'CONSUMIDO' AND `solicitud_id` IS NOT NULL AND `huella_solicitud` IS NOT NULL)
    OR (CAST(`estado` AS BINARY) <> 'CONSUMIDO' AND `solicitud_id` IS NULL AND `huella_solicitud` IS NULL)
  ),
  CONSTRAINT `ck_turno_fechas` CHECK (
    (CAST(`estado` AS BINARY) = 'ESPERANDO' AND `habilitado_en` IS NULL AND `expira_en` IS NULL)
    OR (CAST(`estado` AS BINARY) IN ('HABILITADO', 'CONSUMIDO')
        AND `habilitado_en` IS NOT NULL AND `expira_en` IS NOT NULL AND `expira_en` > `habilitado_en`)
    OR (CAST(`estado` AS BINARY) = 'VENCIDO' AND (
        (`habilitado_en` IS NULL AND `expira_en` IS NULL)
        OR (`habilitado_en` IS NOT NULL AND `expira_en` IS NOT NULL AND `expira_en` > `habilitado_en`)))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
