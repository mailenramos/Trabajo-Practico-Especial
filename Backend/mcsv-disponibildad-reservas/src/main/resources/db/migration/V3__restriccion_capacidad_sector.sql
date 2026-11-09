ALTER TABLE `sectores_disponibilidad`
  ADD CONSTRAINT `ck_sector_capacidad_disponible`
  CHECK (
    `capacidad_total` >= 0
    AND `cantidad_reservada` >= 0
    AND `cantidad_vendida` >= 0
    AND `cantidad_reservada` + `cantidad_vendida` <= `capacidad_total`
  );
