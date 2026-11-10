-- Migración del cambio fila-entera: la fila de un lugar pasa de texto a entero.
-- Correr una vez en cada base existente de compras (las pruebas usan create-drop y no la necesitan).
-- spring.jpa.hibernate.ddl-auto=update no cambia el tipo de una columna que ya existe.
--
-- Uso: mysql -u root -p ticketya_compras < migracion-fila-entera.sql

-- 1. Filas que no son un número entero. Si esta consulta devuelve filas, corregirlas a mano
--    antes de seguir: en modo estricto el ALTER falla y la tabla queda como estaba.
SELECT 'detalles_compra' AS tabla, id, fila
FROM detalles_compra
WHERE fila IS NOT NULL AND fila NOT REGEXP '^-?[0-9]+$'
UNION ALL
SELECT 'solicitudes_devolucion', id, fila
FROM solicitudes_devolucion
WHERE fila IS NOT NULL AND fila NOT REGEXP '^-?[0-9]+$';

-- 2. Cambio de tipo. NULL sigue siendo un lugar de un sector no numerado.
ALTER TABLE detalles_compra MODIFY fila INT NULL;
ALTER TABLE solicitudes_devolucion MODIFY fila INT NULL;

-- Rollback: ALTER TABLE ... MODIFY fila VARCHAR(255) NULL (los enteros pasan a texto sin pérdida).
