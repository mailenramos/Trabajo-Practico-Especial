-- Entrega 2: validación de QR, registro de ingresos y cierre persistente de eventos.

-- 1. Nuevo estado UTILIZADA (la entrada se usó para ingresar).
ALTER TABLE entradas DROP CONSTRAINT IF EXISTS entradas_estado_check;
ALTER TABLE entradas
    ADD CONSTRAINT entradas_estado_check
        CHECK (estado IN ('VIGENTE', 'UTILIZADA', 'EN_REINTEGRO', 'DEVUELTA'));

-- 2. Un asiento usado sigue ocupado: el índice anti doble venta cuenta VIGENTE y UTILIZADA.
DROP INDEX IF EXISTS uk_entrada_lugar_vigente;
CREATE UNIQUE INDEX uk_entrada_lugar_ocupado
    ON entradas (id_evento, id_sector, fila, numero)
    WHERE estado IN ('VIGENTE', 'UTILIZADA') AND numero IS NOT NULL;

-- 3. Fila de control por evento. Existe aunque el evento no tenga entradas y se bloquea para
--    coordinar emisión y validación (bloqueo compartido) con el cierre (bloqueo exclusivo).
CREATE TABLE control_eventos (
    id_evento  VARCHAR(64)              PRIMARY KEY,
    cerrado_el TIMESTAMP WITH TIME ZONE,              -- nulo mientras el evento esté abierto
    creado_el  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- 4. Ingresos registrados al validar un QR. Una entrada se puede usar una sola vez.
CREATE TABLE ingresos (
    id           UUID                     PRIMARY KEY,
    id_entrada   UUID                     NOT NULL REFERENCES entradas (id),
    id_evento    VARCHAR(64)              NOT NULL,
    validado_por VARCHAR(64)              NOT NULL,   -- X-User-Id del personal o administrador
    excepcional  BOOLEAN                  NOT NULL,   -- validación de un ADMIN sin asignación
    validado_el  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ingresos_entrada UNIQUE (id_entrada)
);

CREATE INDEX idx_ingresos_evento ON ingresos (id_evento);
