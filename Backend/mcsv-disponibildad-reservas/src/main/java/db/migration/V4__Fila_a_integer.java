package db.migration;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Converts the legacy textual row columns only after verifying that every
 * existing value is representable by Integer and that the seat business key
 * remains unique after numeric normalization.
 */
public class V4__Fila_a_integer extends BaseJavaMigration {

    private static final BigInteger INTEGER_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INTEGER_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final int MAX_REPORTED_VALUES = 10;
    private static final String UNIQUE_KEY = "uk_asiento_evento_sector_fila_numero";
    private static final String DETAIL_CHECK = "ck_detalle_reserva_forma";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        String asientoFilaType = columnType(connection, "asientos_disponibilidad", "fila");
        String detalleFilaType = columnType(connection, "detalles_reserva", "fila");
        String huellaType = columnType(connection, "eventos_disponibilidad", "huella_inicializacion");

        requireExpectedType(asientoFilaType, "asientos_disponibilidad.fila", "varchar", "int");
        requireExpectedType(detalleFilaType, "detalles_reserva.fila", "varchar", "int");
        requireExpectedType(huellaType, "eventos_disponibilidad.huella_inicializacion", "varchar");
        long huellaLength = columnLength(connection, "eventos_disponibilidad", "huella_inicializacion");
        if (huellaLength != 64 && huellaLength != 67) {
            throw new FlywayException("V4 esperaba eventos_disponibilidad.huella_inicializacion VARCHAR(64) o "
                    + "VARCHAR(67), y encontró VARCHAR(" + huellaLength + "); se detiene para evitar truncar datos");
        }
        requireSeatNullability(connection);
        requireDetailNullability(connection);
        requireUniqueKey(connection);
        requireDetailCheck(connection);

        // Complete every data-dependent check before issuing any DDL.
        validateIntegerValues(connection, "asientos_disponibilidad", false);
        validateIntegerValues(connection, "detalles_reserva", true);
        validateNoSeatKeyCollisions(connection);

        if ("varchar".equalsIgnoreCase(asientoFilaType)) {
            execute(connection, "ALTER TABLE `asientos_disponibilidad` "
                    + "MODIFY COLUMN `fila` INT NOT NULL");
        }
        if ("varchar".equalsIgnoreCase(detalleFilaType)) {
            execute(connection, "ALTER TABLE `detalles_reserva` "
                    + "MODIFY COLUMN `fila` INT DEFAULT NULL");
        }
        if (huellaLength == 64) {
            execute(connection, "ALTER TABLE `eventos_disponibilidad` "
                    + "MODIFY COLUMN `huella_inicializacion` VARCHAR(67) DEFAULT NULL");
        }

        requireColumnType(connection, "asientos_disponibilidad", "fila", "int");
        requireColumnType(connection, "detalles_reserva", "fila", "int");
        requireSeatNullability(connection);
        requireDetailNullability(connection);
        if (columnLength(connection, "eventos_disponibilidad", "huella_inicializacion") != 67) {
            throw new FlywayException("V4 no pudo verificar eventos_disponibilidad.huella_inicializacion VARCHAR(67)");
        }
        requireUniqueKey(connection);
        requireDetailCheck(connection);
    }

    private static void validateIntegerValues(Connection connection, String table, boolean nullable)
            throws SQLException {
        String sql = "SELECT `id`, `fila` FROM `" + table + "` ORDER BY `id`";
        List<String> invalid = new ArrayList<>();
        long invalidCount = 0;

        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String raw = rows.getString("fila");
                if (raw == null) {
                    if (!nullable) {
                        invalidCount++;
                        addSample(invalid, table + ".id=" + id + ": fila es NULL");
                    }
                    continue;
                }

                try {
                    BigInteger value = new BigInteger(raw);
                    if (value.compareTo(INTEGER_MIN) < 0 || value.compareTo(INTEGER_MAX) > 0) {
                        invalidCount++;
                        addSample(invalid, table + ".id=" + id + ": fila fuera del rango Integer (" + raw + ")");
                    }
                } catch (NumberFormatException exception) {
                    invalidCount++;
                    addSample(invalid, table + ".id=" + id + ": fila no es un entero decimal válido (" + raw + ")");
                }
            }
        }

        if (invalidCount > 0) {
            throw new FlywayException("V4 cancelada antes de alterar el esquema: " + invalidCount
                    + " valor(es) de fila no convertibles en " + table + ". Ejemplos: " + String.join("; ", invalid));
        }
    }

    private static void validateNoSeatKeyCollisions(Connection connection) throws SQLException {
        String sql = "SELECT `evento_id`, `sector_id`, CAST(`fila` AS SIGNED) AS `fila_entera`, `numero`, "
                + "COUNT(*) AS `cantidad`, GROUP_CONCAT(`fila` ORDER BY `fila` SEPARATOR ', ') AS `filas` "
                + "FROM `asientos_disponibilidad` "
                + "GROUP BY `evento_id`, `sector_id`, CAST(`fila` AS SIGNED), `numero` "
                + "HAVING COUNT(*) > 1 LIMIT 10";
        List<String> collisions = new ArrayList<>();
        long collisionCount = 0;

        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                collisionCount++;
                addSample(collisions, "eventoId=" + rows.getString("evento_id")
                        + ", sectorId=" + rows.getString("sector_id")
                        + ", fila Integer=" + rows.getInt("fila_entera")
                        + ", numero=" + rows.getInt("numero")
                        + ", filas existentes=[" + rows.getString("filas") + "]");
            }
        }

        if (collisionCount > 0) {
            throw new FlywayException("V4 cancelada antes de alterar el esquema: hay colisiones en "
                    + UNIQUE_KEY + " después de convertir fila a Integer. Ejemplos: "
                    + String.join("; ", collisions));
        }
    }

    private static String columnType(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT `DATA_TYPE` FROM `information_schema`.`COLUMNS` "
                + "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = ? AND `COLUMN_NAME` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new FlywayException("V4 requiere la columna " + table + "." + column);
                }
                return result.getString(1);
            }
        }
    }

    private static long columnLength(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT `CHARACTER_MAXIMUM_LENGTH` FROM `information_schema`.`COLUMNS` "
                + "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = ? AND `COLUMN_NAME` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new FlywayException("V4 requiere la columna " + table + "." + column);
                }
                return result.getLong(1);
            }
        }
    }

    private static void requireExpectedType(String actual, String column, String... expected) {
        for (String type : expected) {
            if (type.equalsIgnoreCase(actual)) {
                return;
            }
        }
        throw new FlywayException("V4 esperaba " + column + " como " + String.join(" o ", expected)
                + " y encontró " + actual);
    }

    private static void requireColumnType(Connection connection, String table, String column, String expected)
            throws SQLException {
        String actual = columnType(connection, table, column);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new FlywayException("V4 no pudo verificar " + table + "." + column + " como " + expected
                    + "; se encontró " + actual);
        }
    }

    private static void requireSeatNullability(Connection connection) throws SQLException {
        String sql = "SELECT `IS_NULLABLE` FROM `information_schema`.`COLUMNS` "
                + "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'asientos_disponibilidad' "
                + "AND `COLUMN_NAME` = 'fila'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || !"NO".equalsIgnoreCase(result.getString(1))) {
                throw new FlywayException("V4 requiere asientos_disponibilidad.fila NOT NULL");
            }
        }
    }

    private static void requireDetailNullability(Connection connection) throws SQLException {
        String sql = "SELECT `IS_NULLABLE` FROM `information_schema`.`COLUMNS` "
                + "WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'detalles_reserva' "
                + "AND `COLUMN_NAME` = 'fila'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || !"YES".equalsIgnoreCase(result.getString(1))) {
                throw new FlywayException("V4 requiere detalles_reserva.fila nullable");
            }
        }
    }

    private static void requireUniqueKey(Connection connection) throws SQLException {
        String sql = "SELECT GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX` SEPARATOR ',') "
                + "FROM `information_schema`.`STATISTICS` WHERE `TABLE_SCHEMA` = DATABASE() "
                + "AND `TABLE_NAME` = 'asientos_disponibilidad' AND `INDEX_NAME` = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UNIQUE_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"evento_id,sector_id,fila,numero".equalsIgnoreCase(result.getString(1))) {
                    throw new FlywayException("V4 requiere conservar la clave única " + UNIQUE_KEY
                            + " sobre (evento_id, sector_id, fila, numero)");
                }
            }
        }
    }

    private static void requireDetailCheck(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM `information_schema`.`TABLE_CONSTRAINTS` "
                + "WHERE `CONSTRAINT_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'detalles_reserva' "
                + "AND `CONSTRAINT_NAME` = ? AND `CONSTRAINT_TYPE` = 'CHECK'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DETAIL_CHECK);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new FlywayException("V4 requiere conservar el CHECK " + DETAIL_CHECK);
                }
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void addSample(List<String> samples, String value) {
        if (samples.size() < MAX_REPORTED_VALUES) {
            samples.add(value);
        }
    }
}
