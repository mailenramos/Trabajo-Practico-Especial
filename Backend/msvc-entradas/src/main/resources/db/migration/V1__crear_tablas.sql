-- Microservicio de entradas: esquema inicial
-- Los ids de evento y sector no son UUID (el servicio de eventos usa MongoDB): se guardan como texto.

CREATE TABLE solicitudes_emision (
                                     id         UUID PRIMARY KEY,
                                     id_compra  UUID      NOT NULL UNIQUE,   -- clave de idempotencia de la emisión
                                     creado_el  TIMESTAMP NOT NULL DEFAULT now()
);

-- Copia mínima (solo lectura) de datos que pertenecen al servicio de eventos
CREATE TABLE copia_evento (
                              id_evento   VARCHAR(64) PRIMARY KEY,
                              nombre      VARCHAR(255) NOT NULL,
                              comienza_en TIMESTAMP    NOT NULL
);

CREATE TABLE copia_sector (
                              id_sector VARCHAR(64) PRIMARY KEY,
                              nombre    VARCHAR(255) NOT NULL
);

CREATE TABLE entradas (
                          id                   UUID PRIMARY KEY,
                          codigo_qr            UUID         NOT NULL UNIQUE,
                          id_solicitud_emision UUID         NOT NULL REFERENCES solicitudes_emision (id),
                          id_compra            UUID         NOT NULL,   -- referencia lógica al servicio de compras
                          id_evento            VARCHAR(64)  NOT NULL REFERENCES copia_evento (id_evento),
                          id_sector            VARCHAR(64)  NOT NULL REFERENCES copia_sector (id_sector),
                          fila                 VARCHAR(50),             -- nulo en sectores no numerados
                          numero               INTEGER,                 -- nulo en sectores no numerados
                          id_cliente           VARCHAR(64)  NOT NULL,   -- referencia lógica al usuario
                          estado               VARCHAR(20)  NOT NULL
                              CHECK (estado IN ('VIGENTE', 'EN_REINTEGRO', 'DEVUELTA')),
                          creado_el            TIMESTAMP    NOT NULL DEFAULT now(),
                          actualizado_el       TIMESTAMP    NOT NULL DEFAULT now(),
    -- Un lugar numerado necesita fila y número
                          CONSTRAINT chk_lugar_completo CHECK (numero IS NULL OR fila IS NOT NULL)
);

-- Regla anti doble venta: un lugar (sector, fila, número) no puede tener dos entradas VIGENTES
-- en el mismo evento. Las entradas en reintegro o devueltas no cuentan, así el lugar puede
-- venderse de nuevo.
CREATE UNIQUE INDEX uk_entrada_lugar_vigente
    ON entradas (id_evento, id_sector, fila, numero)
    WHERE estado = 'VIGENTE' AND numero IS NOT NULL;

-- Índices de consulta
CREATE INDEX idx_entradas_cliente ON entradas (id_cliente);
CREATE INDEX idx_entradas_compra  ON entradas (id_compra);
CREATE INDEX idx_entradas_evento_estado ON entradas (id_evento, estado);
