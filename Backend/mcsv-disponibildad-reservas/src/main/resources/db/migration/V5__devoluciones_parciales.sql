ALTER TABLE `detalles_reserva`
  ADD UNIQUE KEY `uk_detalle_reserva_reserva_id` (`reserva_id`, `id`);

CREATE TABLE `devoluciones` (
  `id` VARCHAR(36) NOT NULL,
  `reserva_id` VARCHAR(36) NOT NULL,
  `huella_solicitud` VARCHAR(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_devolucion_id_reserva` (`id`, `reserva_id`),
  KEY `ix_devolucion_reserva` (`reserva_id`),
  CONSTRAINT `fk_devolucion_reserva`
    FOREIGN KEY (`reserva_id`) REFERENCES `reservas` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `devolucion_detalles_asientos` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `devolucion_id` VARCHAR(36) NOT NULL,
  `reserva_id` VARCHAR(36) NOT NULL,
  `detalle_reserva_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_devolucion_asiento_detalle_original` (`detalle_reserva_id`),
  KEY `ix_devolucion_asiento_devolucion_reserva` (`devolucion_id`, `reserva_id`),
  CONSTRAINT `fk_devolucion_asiento_operacion`
    FOREIGN KEY (`devolucion_id`, `reserva_id`) REFERENCES `devoluciones` (`id`, `reserva_id`),
  CONSTRAINT `fk_devolucion_asiento_origen`
    FOREIGN KEY (`reserva_id`, `detalle_reserva_id`) REFERENCES `detalles_reserva` (`reserva_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `devolucion_detalles_cantidades` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `devolucion_id` VARCHAR(36) NOT NULL,
  `reserva_id` VARCHAR(36) NOT NULL,
  `detalle_reserva_id` BIGINT NOT NULL,
  `cantidad` INT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_devolucion_cantidad_detalle` (`devolucion_id`, `detalle_reserva_id`),
  KEY `ix_devolucion_cantidad_origen` (`reserva_id`, `detalle_reserva_id`),
  CONSTRAINT `ck_devolucion_cantidad_positiva` CHECK (`cantidad` > 0),
  CONSTRAINT `fk_devolucion_cantidad_operacion`
    FOREIGN KEY (`devolucion_id`, `reserva_id`) REFERENCES `devoluciones` (`id`, `reserva_id`),
  CONSTRAINT `fk_devolucion_cantidad_origen`
    FOREIGN KEY (`reserva_id`, `detalle_reserva_id`) REFERENCES `detalles_reserva` (`reserva_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
