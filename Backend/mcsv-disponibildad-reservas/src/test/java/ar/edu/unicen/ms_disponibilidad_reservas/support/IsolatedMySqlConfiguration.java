package ar.edu.unicen.ms_disponibilidad_reservas.support;

import java.util.Properties;

/** Explicit disposable-server opt-in; never falls back to application credentials. */
public final class IsolatedMySqlConfiguration {
    private IsolatedMySqlConfiguration() { }

    public static Properties load() {
        String url = System.getenv("MS2_TEST_MYSQL_URL");
        String password = System.getenv("MS2_TEST_MYSQL_PASSWORD");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ms2_disposable")
                || url.contains(":3307/") || password == null) {
            throw new IllegalStateException("Supply a dedicated disposable MySQL server through MS2_TEST_MYSQL_URL and MS2_TEST_MYSQL_PASSWORD; local configuration is never used");
        }
        Properties properties = new Properties();
        properties.setProperty("spring.datasource.url", url);
        properties.setProperty("spring.datasource.username", "root");
        properties.setProperty("spring.datasource.password", password);
        return properties;
    }
}
