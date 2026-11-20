CREATE TABLE usuarios (
    id              UUID                     NOT NULL PRIMARY KEY,
    nombre          VARCHAR(100)             NOT NULL,
    apellido        VARCHAR(100)             NOT NULL,
    -- Se guarda normalizado (sin espacios al borde y en minúsculas).
    correo          VARCHAR(254)             NOT NULL,
    password_hash   VARCHAR(100)             NOT NULL,
    activo          BOOLEAN                  NOT NULL,
    creado_el       TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_el  TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT uk_usuarios_correo UNIQUE (correo),
    CONSTRAINT ck_usuarios_correo_normalizado CHECK (correo = LOWER(TRIM(correo)))
);

CREATE TABLE usuario_roles (
    usuario_id  UUID        NOT NULL,
    rol         VARCHAR(20) NOT NULL,
    CONSTRAINT pk_usuario_roles PRIMARY KEY (usuario_id, rol),
    CONSTRAINT fk_usuario_roles_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios (id),
    CONSTRAINT ck_usuario_roles_rol CHECK (rol IN ('CLIENTE', 'ORGANIZADOR', 'ADMIN', 'PERSONAL_ACCESO'))
);

CREATE INDEX ix_usuario_roles_rol ON usuario_roles (rol);
