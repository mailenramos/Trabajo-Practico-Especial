package ar.edu.unicen.ms_disponibilidad_reservas.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

/** Exercises the V4 data guard and DDL only in uniquely named temporary schemas. */
@EnabledIfSystemProperty(named = "ms2.mysql.integration", matches = "true")
class FilaIntegerMySqlMigrationTests {

    @Test
    void convertsBothRowColumnsAndPreservesKeysChecksAndLegacyFingerprint() throws Exception {
        try (TemporaryDatabase database = TemporaryDatabase.create()) {
            migrateToV3(database);
            seedReservation(database);
            execute(database, "INSERT INTO asientos_disponibilidad "
                    + "(evento_id, sector_id, fila, numero, estado) VALUES "
                    + "('evento-v4', 'sector-v4', '01', 1, 'LIBRE'), "
                    + "('evento-v4', 'sector-v4', '10', 2, 'LIBRE'), "
                    + "('evento-v4', 'sector-v4', '-1', 3, 'LIBRE'), "
                    + "('evento-v4', 'sector-v4', '0', 4, 'LIBRE')");
            execute(database, "INSERT INTO detalles_reserva "
                    + "(reserva_id, sector_id, fila, numero, cantidad) VALUES "
                    + "('00000000-0000-0000-0000-000000000001', 'sector-v4', '7', 7, 1), "
                    + "('00000000-0000-0000-0000-000000000001', 'general-v4', NULL, NULL, 3)");
            execute(database, "INSERT INTO eventos_disponibilidad "
                    + "(estado_evento, evento_id, fecha_creacion, limite_entradas_por_cliente, "
                    + "venta_suspendida, huella_inicializacion) VALUES "
                    + "('PENDIENTE_PUBLICACION', 'evento-v4', CURRENT_TIMESTAMP, 4, 0, '"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')");

            String constraintsBeforeV4 = database.scalar(constraintSnapshotSql());
            migrateToV4(database);

            assertEquals("8.4.13", database.scalar("SELECT VERSION()"));
            assertEquals("int", columnType(database, "asientos_disponibilidad", "fila"));
            assertEquals("int", columnType(database, "detalles_reserva", "fila"));
            assertEquals("NO", database.scalar("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asientos_disponibilidad' AND COLUMN_NAME = 'fila'"));
            assertEquals("YES", database.scalar("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'detalles_reserva' AND COLUMN_NAME = 'fila'"));
            assertEquals("-1,0,1,10", database.scalar("SELECT GROUP_CONCAT(fila ORDER BY fila) "
                    + "FROM asientos_disponibilidad WHERE evento_id = 'evento-v4'"));
            assertEquals("7,1", database.scalar("SELECT CONCAT((SELECT fila FROM detalles_reserva WHERE numero = 7), ',', "
                    + "(SELECT COUNT(*) FROM detalles_reserva WHERE fila IS NULL))"));
            assertEquals(67, columnLength(database, "eventos_disponibilidad", "huella_inicializacion"));
            assertEquals(1, database.integer("SELECT COUNT(*) FROM eventos_disponibilidad "
                    + "WHERE evento_id = 'evento-v4' AND huella_inicializacion = '"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'"));
            assertEquals(constraintsBeforeV4, database.scalar(constraintSnapshotSql()),
                    "V4 debe conservar todas las PK, UNIQUE, FK y CHECK existentes");
            assertEquals("evento_id,sector_id,fila,numero", database.scalar("SELECT GROUP_CONCAT(COLUMN_NAME "
                    + "ORDER BY SEQ_IN_INDEX SEPARATOR ',') FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asientos_disponibilidad' "
                    + "AND INDEX_NAME = 'uk_asiento_evento_sector_fila_numero'"));
            assertEquals(1, database.integer("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'detalles_reserva' "
                    + "AND CONSTRAINT_NAME = 'ck_detalle_reserva_forma' AND CONSTRAINT_TYPE = 'CHECK'"));
            assertEquals(1, database.integer("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'asientos_disponibilidad' "
                    + "AND CONSTRAINT_TYPE = 'CHECK'"));
            assertEquals(1, database.integer("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'detalles_reserva' "
                    + "AND CONSTRAINT_TYPE = 'FOREIGN KEY'"));
            assertEquals(1, database.integer("SELECT COUNT(*) FROM detalles_reserva WHERE fila IS NULL AND numero IS NULL"));
            assertThrows(SQLException.class, () -> execute(database, "INSERT INTO detalles_reserva "
                    + "(reserva_id, sector_id, fila, numero, cantidad) VALUES "
                    + "('00000000-0000-0000-0000-000000000001', 'invalid-v4', NULL, 7, 1)"));
            assertThrows(SQLException.class, () -> execute(database, "INSERT INTO asientos_disponibilidad "
                    + "(evento_id, sector_id, fila, numero, estado) "
                    + "VALUES ('evento-v4', 'sector-v4', 1, 1, 'LIBRE')"));
            assertThrows(SQLException.class, () -> execute(database, "INSERT INTO asientos_disponibilidad "
                    + "(evento_id, sector_id, fila, numero, estado) "
                    + "VALUES ('evento-v4', 'sector-v4', 50, 0, 'LIBRE')"));
            assertThrows(SQLException.class, () -> execute(database, "INSERT INTO sectores_disponibilidad "
                    + "(cantidad_reservada, cantidad_vendida, capacidad_total, evento_id, sector_id, tipo) "
                    + "VALUES (2, 0, 1, 'evento-v4', 'invalid-capacity', 'NO_NUMERADO')"));
        }
    }

    @Test
    void migrationRejectsNonnumericRowsBeforeChangingAnyColumn() throws Exception {
        assertMigrationRefuses("INSERT INTO asientos_disponibilidad "
                + "(evento_id, sector_id, fila, numero, estado) VALUES ('e', 's', 'A', 1, 'LIBRE')",
                "fila no es un entero decimal válido");
    }

    @Test
    void migrationRejectsRowsOutsideIntegerRangeBeforeChangingAnyColumn() throws Exception {
        assertMigrationRefuses("INSERT INTO asientos_disponibilidad "
                + "(evento_id, sector_id, fila, numero, estado) VALUES ('e', 's', '2147483648', 1, 'LIBRE')",
                "fila fuera del rango Integer");
    }

    @Test
    void migrationRejectsCompositeKeyCollisionsAfterNumericConversion() throws Exception {
        assertMigrationRefuses("INSERT INTO asientos_disponibilidad "
                + "(evento_id, sector_id, fila, numero, estado) VALUES "
                + "('e', 's', '01', 1, 'LIBRE'), ('e', 's', '1', 1, 'LIBRE')",
                "colisiones en uk_asiento_evento_sector_fila_numero");
    }

    @Test
    void migrationRejectsNonnumericDetailRowsBeforeChangingAnyColumn() throws Exception {
        try (TemporaryDatabase database = TemporaryDatabase.create()) {
            migrateToV3(database);
            seedReservation(database);
            execute(database, "INSERT INTO detalles_reserva "
                    + "(reserva_id, sector_id, fila, numero, cantidad) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', 's', 'PB', 1, 1)");

            FlywayException error = assertThrows(FlywayException.class, () -> migrateToLatest(database));
            assertTrue(exceptionMessages(error).contains("fila no es un entero decimal válido"));
            assertEquals("varchar", columnType(database, "asientos_disponibilidad", "fila"));
            assertEquals("varchar", columnType(database, "detalles_reserva", "fila"));
        }
    }

    private static void assertMigrationRefuses(String seedSql, String diagnostic) throws Exception {
        try (TemporaryDatabase database = TemporaryDatabase.create()) {
            migrateToV3(database);
            execute(database, seedSql);

            FlywayException error = assertThrows(FlywayException.class, () -> migrateToLatest(database));

            assertTrue(exceptionMessages(error).contains(diagnostic), exceptionMessages(error));
            assertEquals("varchar", columnType(database, "asientos_disponibilidad", "fila"));
            assertEquals("varchar", columnType(database, "detalles_reserva", "fila"));
        }
    }

    private static String exceptionMessages(Throwable error) {
        return java.util.stream.Stream.iterate(error, current -> current != null, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(message -> message != null)
                .collect(Collectors.joining(" | "));
    }

    private static void migrateToV3(TemporaryDatabase database) {
        Flyway.configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate();
    }

    private static void migrateToLatest(TemporaryDatabase database) {
        Flyway.configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void migrateToV4(TemporaryDatabase database) {
        Flyway.configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("4"))
                .load()
                .migrate();
    }

    private static void seedReservation(TemporaryDatabase database) throws SQLException {
        execute(database, "INSERT INTO reservas "
                + "(id, clave_idempotencia, estado, evento_id, expira_en, huella_solicitud, usuario_id) "
                + "VALUES ('00000000-0000-0000-0000-000000000001', 'key-v4', 'VIGENTE', 'evento-v4', "
                + "DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE), 'request-hash', 'cliente-v4')");
    }

    private static void execute(TemporaryDatabase database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.jdbcUrl, database.username,
                database.password); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String columnType(TemporaryDatabase database, String table, String column) throws SQLException {
        return database.scalar("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static long columnLength(TemporaryDatabase database, String table, String column) throws SQLException {
        return database.integer("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static String constraintSnapshotSql() {
        return "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, ':', CONSTRAINT_NAME, ':', CONSTRAINT_TYPE) "
                + "ORDER BY TABLE_NAME, CONSTRAINT_NAME SEPARATOR '|') FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE()";
    }

    private static final class TemporaryDatabase implements AutoCloseable {
        private final String adminUrl;
        private final String jdbcUrl;
        private final String schema;
        private final String username;
        private final String password;

        private TemporaryDatabase(String adminUrl, String jdbcUrl, String schema, String username, String password) {
            this.adminUrl = adminUrl;
            this.jdbcUrl = jdbcUrl;
            this.schema = schema;
            this.username = username;
            this.password = password;
        }

        private static TemporaryDatabase create() throws IOException, SQLException {
            Properties properties = ar.edu.unicen.ms_disponibilidad_reservas.support.IsolatedMySqlConfiguration.load();

            String configuredUrl = properties.getProperty("spring.datasource.url");
            String prefix = "jdbc:mysql://";
            if (configuredUrl == null || !configuredUrl.startsWith(prefix)) {
                throw new IllegalStateException("La configuración no apunta a MySQL; no se crea esquema temporal");
            }
            String remainder = configuredUrl.substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash <= 0) {
                throw new IllegalStateException("La URL MySQL no contiene host y puerto; no se crea esquema temporal");
            }
            String hostAndPort = remainder.substring(0, slash);
            String query = remainder.substring(slash + 1);
            int queryStart = query.indexOf('?');
            query = queryStart >= 0 ? query.substring(queryStart) : "";
            String schema = "ms2_fila_v4_" + UUID.randomUUID().toString().replace("-", "");
            String adminUrl = prefix + hostAndPort + "/" + query;
            String jdbcUrl = prefix + hostAndPort + "/" + schema + query;
            String username = properties.getProperty("spring.datasource.username");
            String password = properties.getProperty("spring.datasource.password", "");
            TemporaryDatabase database = new TemporaryDatabase(adminUrl, jdbcUrl, schema, username, password);
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            }
            return database;
        }

        private String scalar(String sql) throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                    Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new SQLException("La consulta de verificación no devolvió filas");
                }
                return result.getString(1);
            }
        }

        private int integer(String sql) throws SQLException {
            return Integer.parseInt(scalar(sql));
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }
}
