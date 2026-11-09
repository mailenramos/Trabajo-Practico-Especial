# Despliegue seguro de V7 y compatibilidad histórica

Este procedimiento es para una intervención posterior autorizada. No se ejecutó V7 ni se consultaron datos de bases persistentes durante esta revisión.

## Alcance de V7

V7 cambia 12 columnas VARCHAR(255) NOT NULL a utf8mb4_0900_bin (comparación binaria, NO PAD):

| Tabla | Columnas |
|---|---|
| eventos_disponibilidad | evento_id |
| sectores_disponibilidad | evento_id, sector_id |
| asientos_disponibilidad | evento_id, sector_id |
| reservas | evento_id, usuario_id, clave_idempotencia |
| detalles_reserva | sector_id |
| cantidades_cliente_evento | evento_id, usuario_id |
| notificaciones_disponibilidad_outbox | evento_id |

No cambia longitudes, nulabilidad, PK, FK, CHECK, estados, cantidades, IDs UUID ni columnas de huellas. Conserva UNIQUE de evento, evento/sector, asiento compuesto, usuario/solicitud y usuario/evento, pero cambia su igualdad de comparación. No se elimina ni normaliza información. Los índices afectados pueden reconstruirse y los ALTER pueden bloquear tablas o quedar parcialmente aplicados si ocurre un fallo; se necesita ventana de mantenimiento y espacio suficiente.

Pasar de igualdad insensible a igualdad exacta no fusiona IDs ni introduce por sí mismo duplicados en UNIQUE: permite distinguir valores que antes eran equivalentes. Sí puede separar referencias escalares históricas: un evento almacenado como EVT y un sector con evento_id evt podían vincularse por consultas anteriores y dejar de hacerlo. No hay FK física entre estas columnas externas. Debe auditarse esa consistencia antes del despliegue.

## Huellas: dos problemas distintos

- Inicialización v2: incluye cadenas exactas, límite y asientos normalizados/ordenados. Solicitud de reserva v1: incluye evento, solicitudId y detalles con su orden. V7 no modifica sus bytes ni los algoritmos. Mismo contenido e identificadores exactos conserva la huella.
- Inicialización antigua sin prefijo v2: ya se rechaza de forma cerrada con 409; es un pendiente previo a V7, no una incompatibilidad nueva de esta migración. El inventario actual puede haber cambiado por reservas y no permite reconstruir con certeza la solicitud inicial.
- Antes de V7, usuarioId/solicitudId con grafías distintas podían localizar una misma reserva mediante la collation. usuarioId no forma parte de la huella de solicitud. Después, una variante puede no localizar la reserva y ser tratada como una solicitud nueva. Cambiar la grafía del evento o sector puede además alterar huellas o producir 404/409. Los contadores cliente/evento también pueden quedar separados por alias históricos.
- Las huellas de devolución y sus UUID no se modifican en V7. Sus comparaciones de reserva/detalles siguen siendo las existentes.

No se consultó la base persistente: no puede afirmarse que existan o no alias en sus registros actuales.

### Estrategia compatible propuesta, no implementada

Conservar huellas y filas originales. Coordinar que productores y reintentos utilicen exactamente los identificadores originales, sin cambiar silenciosamente mayúsculas, acentos ni espacios. Auditar referencias y contrastarlas con la identidad autorizada por los productores. Si hay alias históricos, bloquear el despliegue hasta aprobar su tratamiento: una tabla de equivalencias histórica, explícita, limitada a registros previos al corte y validada por el responsable de identidad es una opción, no una decisión aplicada. Nunca usar una búsqueda insensible genérica como fallback, porque podría mezclar clientes distintos y permitir dobles reservas.

Para huellas anteriores a v2, recuperar el payload inicial auténtico desde una fuente histórica confiable y diseñar un comparador compatible versionado que no sobrescriba la evidencia. Si no existe ese payload, conservar el conflicto cerrado hasta decisión del equipo. No regenerar huellas desde el inventario actual ni deducir equivalencias solo por similitud textual.

## Preparación e inventario (solo lectura)

1. Identificar y registrar servidor, base destino y responsable del entorno. Detener MS2 y todos los escritores. No utilizar una base compartida sin ventana autorizada. Verificar conexiones y eventos programados.
2. Confirmar que el historial termina en V6 exitosa y que V7 es la única pendiente. No ejecutar baseline ni repair. Confirmar fuente, compilado de V4 y SQL V7 de la versión que se desplegará.
3. Guardar inventario completo de columnas, índices, PK/FK/CHECK y cantidades por tabla; guardar IDs y huellas en un inventario protegido para comparar byte a byte después. No incluir credenciales.

```sql
SELECT DATABASE(), VERSION();
SELECT ID, USER, HOST, DB, COMMAND FROM information_schema.processlist
WHERE ID <> CONNECTION_ID();
SELECT EVENT_NAME, STATUS FROM information_schema.events WHERE EVENT_SCHEMA=DATABASE();
SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME
FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE();
SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME
FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE();
SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME
FROM information_schema.key_column_usage WHERE CONSTRAINT_SCHEMA=DATABASE();
SELECT t.TABLE_NAME,t.CONSTRAINT_NAME,c.CHECK_CLAUSE,t.ENFORCED
FROM information_schema.table_constraints t JOIN information_schema.check_constraints c
ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
WHERE t.CONSTRAINT_SCHEMA=DATABASE();
SELECT id,HEX(evento_id),HEX(huella_inicializacion) FROM eventos_disponibilidad;
SELECT id,HEX(evento_id),HEX(usuario_id),HEX(clave_idempotencia),HEX(huella_solicitud) FROM reservas;
```

Repetir COUNT(*) para las diez tablas de aplicación y comparar después. Antes de V7, buscar referencias externas con igualdad antigua pero bytes distintos; ejemplo para sectores (repetir para asientos, reservas, cantidades y outbox, y sector_id en detalles/asientos):

```sql
SELECT s.id,HEX(s.evento_id),HEX(e.evento_id)
FROM sectores_disponibilidad s JOIN eventos_disponibilidad e ON e.evento_id=s.evento_id
WHERE HEX(s.evento_id)<>HEX(e.evento_id);
```

Revisar también referencias sin padre y grafías de usuario/solicitud contra las fuentes de identidad originales. El esquema por sí solo no demuestra que dos IDs diferentes sean la misma persona o solicitud.

## Respaldo completo y restaurabilidad

Ejemplo manual para el contenedor local conocido; confirmar el destino antes de utilizarlo. Los siguientes comandos no se ejecutaron en esta revisión. La contraseña se solicita oculta y permanece en el entorno del proceso, no en argumentos ni archivos.

```zsh
read -s "MYSQL_PWD?Contraseña MySQL: "
printf '\n'
export MYSQL_PWD
BACKUP_DIR="$HOME/ms2-backups/pre-v7-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
if docker exec -e MYSQL_PWD mysql-disponibilidad mysqldump --user=root \
  --single-transaction --quick --routines --events --triggers --hex-blob \
  --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  --skip-add-drop-table disponibilidad_reservas > "$BACKUP_DIR/disponibilidad_reservas.sql"
then
  test -s "$BACKUP_DIR/disponibilidad_reservas.sql"
  tail -n 5 "$BACKUP_DIR/disponibilidad_reservas.sql"
  shasum -a 256 "$BACKUP_DIR/disponibilidad_reservas.sql" > "$BACKUP_DIR/disponibilidad_reservas.sql.sha256"
  shasum -a 256 -c "$BACKUP_DIR/disponibilidad_reservas.sql.sha256"
else
  printf 'Respaldo fallido: detener despliegue.\n'
fi
unset MYSQL_PWD
```

Solo continuar con exit code exitoso, marcador final del dump, checksum coincidente y restauración ensayada en una base nueva aislada, nunca sobre una existente. El dump incluye flyway_schema_history. Conservarlo fuera de ubicaciones temporales y comparar la restauración con el inventario. Cualquier diferencia o alias sin resolver detiene el despliegue.

## Aplicación posterior autorizada

Con la versión compilada y su classpath de proyecto, usar la API Java de Flyway sin iniciar Spring Boot. Leer URL/usuario/contraseña exclusivamente en memoria; verificar el nombre exacto de la base y otras conexiones inmediatamente antes. Obtener info().pending() y exigir que solo figure 7. La llamada prevista es:

```java
Flyway flyway = Flyway.configure()
    .dataSource(urlVerificada, usuario, passwordEnMemoria)
    .locations("classpath:db/migration")
    .baselineOnMigrate(false).outOfOrder(false).target("7").load();
// Comprobar únicamente V7 pendiente antes de continuar.
flyway.migrate(); // Una sola ejecución autorizada.
```

No ejecutar los ALTER manualmente ni cambiar V1–V7 ya revisadas. No aplicar este procedimiento automáticamente como parte de una revisión o prueba.

## Verificación y fallo

Tras éxito, solo SELECT: V1 baseline y V2–V7 exitosas; mismas tablas, recuentos, IDs y huellas byte a byte; 12 columnas con utf8mb4_0900_bin; mismos índices/UNIQUE, FK y CHECK. Confirmar que los reintentos exactos conservan identidad en el entorno aislado restaurado antes de habilitar tráfico persistente.

Si hay error, detenerse y mantener escritores detenidos. No repetir migrate, no repair, no cambiar historial ni intentar revertir automáticamente la collation: tras nuevas escrituras exactas podrían existir IDs que confluyan al volver a una collation insensible. Documentar los ALTER completados y comparar metadatos. La restauración o reparación exige aprobación específica; no ejecutar comandos destructivos como parte de este procedimiento.
