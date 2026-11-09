ALTER TABLE entradas
ALTER COLUMN fila TYPE INTEGER
USING fila::INTEGER;

ALTER TABLE entradas
DROP CONSTRAINT chk_lugar_completo;

ALTER TABLE entradas
    ADD CONSTRAINT chk_lugar_completo CHECK (
        (fila IS NULL AND numero IS NULL)
            OR
        (fila IS NOT NULL AND numero IS NOT NULL)
        );