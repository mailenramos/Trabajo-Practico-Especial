package com.ticketya.gestionlugares.trazabilidad;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class Correlacion {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private Correlacion() {
    }

    public static String validarOGenerar(String valor) {
        return valor != null && ID_VALIDO.matcher(valor).matches() ? valor : UUID.randomUUID().toString();
    }

    public static String actual() {
        return MDC.get(MDC_KEY);
    }

    public static Contexto usar(String valor) {
        String anterior = actual();
        MDC.put(MDC_KEY, validarOGenerar(valor));
        return new Contexto(anterior);
    }

    public static final class Contexto implements AutoCloseable {
        private final String anterior;

        private Contexto(String anterior) {
            this.anterior = anterior;
        }

        @Override
        public void close() {
            if (anterior == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, anterior);
        }
    }
}
