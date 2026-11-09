package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifica por separado el camino heredado de actualización de asientos sin Flyway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"
})
@EnabledIfSystemProperty(named = "ms2.mysql.integration", matches = "true")
class DisponibilidadMySqlLegacyBackfillTests {

    private static LegacyDatabase database;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureLegacySchema(DynamicPropertyRegistry registry) {
        database = LegacyDatabase.create();
        registry.add("spring.datasource.url", () -> database.jdbcUrl);
        registry.add("spring.datasource.username", () -> database.username);
        registry.add("spring.datasource.password", () -> database.password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Test
    void legacySeatWithoutStateIsBackfilledAsFreeInAnIsolatedSchema() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version.startsWith("8.4.13"), "Se esperaba MySQL 8.4.13 y se obtuvo " + version);
        assertEquals("LIBRE", jdbcTemplate.queryForObject(
                "SELECT estado FROM asientos_disponibilidad WHERE evento_id = 'evento-previo' "
                        + "AND sector_id = 'sector-previo' AND fila = 1 AND numero = 1", String.class));
    }

    private static final class LegacyDatabase {
        private final String adminUrl;
        private final String jdbcUrl;
        private final String schema;
        private final String username;
        private final String password;

        private LegacyDatabase(String adminUrl, String jdbcUrl, String schema, String username, String password) {
            this.adminUrl = adminUrl;
            this.jdbcUrl = jdbcUrl;
            this.schema = schema;
            this.username = username;
            this.password = password;
        }

        private static LegacyDatabase create() {
            Properties properties = new Properties();
            try (var input = new ClassPathResource("application.properties").getInputStream()) {
                properties.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer la configuración MySQL", e);
            }

            String configuredUrl = properties.getProperty("spring.datasource.url");
            String prefix = "jdbc:mysql://";
            if (configuredUrl == null || !configuredUrl.startsWith(prefix)) {
                throw new IllegalStateException("La URL configurada no corresponde a MySQL");
            }
            String remainder = configuredUrl.substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash < 1) {
                throw new IllegalStateException("La URL configurada no contiene host y puerto MySQL");
            }
            String hostAndPort = remainder.substring(0, slash);
            String suffix = remainder.substring(slash + 1);
            int queryStart = suffix.indexOf('?');
            String query = queryStart >= 0 ? suffix.substring(queryStart) : "";
            String schema = "ms2_legacy_" + UUID.randomUUID().toString().replace("-", "");
            String adminUrl = prefix + hostAndPort + "/" + query;
            String jdbcUrl = prefix + hostAndPort + "/" + schema + query;
            String username = properties.getProperty("spring.datasource.username");
            String password = properties.getProperty("spring.datasource.password", "");
            LegacyDatabase database = new LegacyDatabase(adminUrl, jdbcUrl, schema, username, password);
            database.createLegacySeatTable();
            Runtime.getRuntime().addShutdownHook(new Thread(database::drop));
            return database;
        }

        private void createLegacySeatTable() {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo crear el esquema heredado temporal", e);
            }

            String ddl = "CREATE TABLE asientos_disponibilidad ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "evento_id VARCHAR(255) NOT NULL, sector_id VARCHAR(255) NOT NULL, "
                    + "fila VARCHAR(255) NOT NULL, numero INT NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_asiento_evento_sector_fila_numero (evento_id, sector_id, fila, numero), "
                    + "CONSTRAINT ck_asiento_numero_positivo CHECK (numero >= 1)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute(ddl);
                statement.executeUpdate("INSERT INTO asientos_disponibilidad "
                        + "(evento_id, sector_id, fila, numero) VALUES ('evento-previo', 'sector-previo', 1, 1)");
            } catch (SQLException e) {
                drop();
                throw new IllegalStateException("No se pudo preparar la tabla heredada de asientos", e);
            }
        }

        private void drop() {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            } catch (SQLException ignored) {
                // Schema efímero, aislado y exclusivo de esta prueba.
            }
        }
    }
}
