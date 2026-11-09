package ar.edu.unicen.ms_disponibilidad_reservas.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in: the runner must supply a fresh MySQL container, never the working database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=false", "spring.jpa.show-sql=false",
        "ms2.reservas.expiracion.fixed-delay-ms=3600000", "ms2.outbox.fixed-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "MS2_R2_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms2_r2_disposable")
class R2MySqlLockOrderTests extends R2LockOrderTests {
    @DynamicPropertySource
    static void disposableDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("MS2_R2_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms2_r2_disposable") || url.contains(":3307/")) {
            throw new IllegalStateException("A dedicated disposable MySQL server is required");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> System.getenv("MS2_R2_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }
    @org.junit.jupiter.api.Test
    void historicalReservationCounterInversionProducesMysqlDeadlock() throws Exception {
        String event = init();
        var ids = new java.util.ArrayList<>(java.util.List.of(hold(event, 1), hold(event, 2)));
        java.util.Collections.sort(ids);
        var before = snapshot();
        try (var bulk = db.getDataSource().getConnection(); var individual = db.getDataSource().getConnection()) {
            bulk.setAutoCommit(false); individual.setAutoCommit(false);
            lock(bulk, "SELECT id FROM eventos_disponibilidad WHERE evento_id=? FOR UPDATE", event);
            lock(bulk, "SELECT id FROM reservas WHERE id=? FOR UPDATE", ids.get(0));
            lock(individual, "SELECT id FROM reservas WHERE id=? FOR UPDATE", ids.get(1));
            lock(bulk, "SELECT id FROM cantidades_cliente_evento WHERE evento_id=? FOR UPDATE", event);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> closeHistoricalCycle(bulk, start,
                        "SELECT id FROM reservas WHERE id=? FOR UPDATE", ids.get(1)));
                var b = pool.submit(() -> closeHistoricalCycle(individual, start,
                        "SELECT id FROM cantidades_cliente_evento WHERE evento_id=? FOR UPDATE", event));
                start.countDown();
                var codes = java.util.List.of(a.get(15, java.util.concurrent.TimeUnit.SECONDS),
                        b.get(15, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertTrue(codes.contains(1213), "MySQL must detect the historical wait cycle");
                org.junit.jupiter.api.Assertions.assertTrue(codes.contains(0), "The surviving transaction must finish");
            } finally { bulk.rollback(); individual.rollback(); }
        }
        org.junit.jupiter.api.Assertions.assertEquals(before, snapshot());
    }
    private static void lock(java.sql.Connection connection, String sql, String key) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10); statement.setString(1, key);
            try (var rows = statement.executeQuery()) { org.junit.jupiter.api.Assertions.assertTrue(rows.next()); }
        }
    }
    private static int closeHistoricalCycle(java.sql.Connection connection, java.util.concurrent.CountDownLatch start,
            String sql, String key) throws Exception {
        try { await(start); lock(connection, sql, key); return 0; }
        catch (java.sql.SQLException error) {
            org.junit.jupiter.api.Assertions.assertEquals(1213, error.getErrorCode());
            return error.getErrorCode();
        } finally { connection.rollback(); }
    }

}
