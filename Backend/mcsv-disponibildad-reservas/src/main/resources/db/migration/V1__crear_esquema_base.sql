CREATE TABLE `asientos_disponibilidad` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `evento_id` VARCHAR(255) NOT NULL,
  `fila` VARCHAR(255) NOT NULL,
  `numero` INT NOT NULL,
  `sector_id` VARCHAR(255) NOT NULL,
  `estado` VARCHAR(16) NOT NULL DEFAULT 'LIBRE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asiento_evento_sector_fila_numero` (`evento_id`, `sector_id`, `fila`, `numero`),
  CONSTRAINT `asientos_disponibilidad_chk_1` CHECK (`numero` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cantidades_cliente_evento` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `cantidad_confirmada` INT NOT NULL,
  `cantidad_reservada` INT NOT NULL,
  `evento_id` VARCHAR(255) NOT NULL,
  `usuario_id` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cantidad_cliente_evento_usuario_evento` (`usuario_id`, `evento_id`),
  CONSTRAINT `cantidades_cliente_evento_chk_1` CHECK (`cantidad_confirmada` >= 0),
  CONSTRAINT `cantidades_cliente_evento_chk_2` CHECK (`cantidad_reservada` >= 0),
  CONSTRAINT `ck_cantidad_cliente_evento_no_negativa`
    CHECK (`cantidad_reservada` >= 0 AND `cantidad_confirmada` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `eventos_disponibilidad` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `estado_evento` ENUM('AGOTADO','CANCELADO','FINALIZADO','PUBLICADO') NOT NULL,
  `evento_id` VARCHAR(255) NOT NULL,
  `fecha_creacion` DATETIME(6) NOT NULL,
  `limite_entradas_por_cliente` INT DEFAULT NULL,
  `venta_suspendida` BIT(1) NOT NULL,
  `huella_inicializacion` VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKix2amb63v852cmagap9ca3gm4` (`evento_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reservas` (
  `id` VARCHAR(36) NOT NULL,
  `clave_idempotencia` VARCHAR(255) NOT NULL,
  `estado` ENUM('CONFIRMADA','LIBERADA','VIGENTE') NOT NULL,
  `evento_id` VARCHAR(255) NOT NULL,
  `expira_en` DATETIME(6) NOT NULL,
  `huella_solicitud` VARCHAR(255) NOT NULL,
  `usuario_id` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reserva_usuario_clave_idempotencia` (`usuario_id`, `clave_idempotencia`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `detalles_reserva` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `cantidad` INT NOT NULL,
  `fila` VARCHAR(255) DEFAULT NULL,
  `numero` INT DEFAULT NULL,
  `sector_id` VARCHAR(255) NOT NULL,
  `reserva_id` VARCHAR(36) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKtrenodldgngh4q2m4yyv3b2b2` (`reserva_id`),
  CONSTRAINT `FKtrenodldgngh4q2m4yyv3b2b2`
    FOREIGN KEY (`reserva_id`) REFERENCES `reservas` (`id`),
  CONSTRAINT `ck_detalle_reserva_forma`
    CHECK ((`fila` IS NOT NULL AND `numero` IS NOT NULL AND `numero` >= 1 AND `cantidad` = 1)
        OR (`fila` IS NULL AND `numero` IS NULL AND `cantidad` > 0)),
  CONSTRAINT `detalles_reserva_chk_1` CHECK (`numero` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sectores_disponibilidad` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `cantidad_reservada` INT NOT NULL,
  `cantidad_vendida` INT NOT NULL,
  `capacidad_total` INT NOT NULL,
  `evento_id` VARCHAR(255) NOT NULL,
  `sector_id` VARCHAR(255) NOT NULL,
  `tipo` ENUM('NO_NUMERADO','NUMERADO') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKqw41ubuf4se6vysitt25kss7f` (`evento_id`, `sector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
