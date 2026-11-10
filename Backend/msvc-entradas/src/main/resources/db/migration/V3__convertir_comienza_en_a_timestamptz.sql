-- Las filas anteriores no conservan zona. Se asume que representan el instante UTC
-- enviado por MS3 como Instant; la conversión mantiene explícitamente ese instante.
ALTER TABLE copia_evento
    ALTER COLUMN comienza_en TYPE TIMESTAMP WITH TIME ZONE
    USING comienza_en AT TIME ZONE 'UTC';
