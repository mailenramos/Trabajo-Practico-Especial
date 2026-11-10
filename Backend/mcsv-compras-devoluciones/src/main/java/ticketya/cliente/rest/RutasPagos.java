package ticketya.cliente.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rutas de Pagos (propiedades servicios.pagos.rutas.*). Por defecto, las rutas internas
 * de Pagos. Cada ruta es una plantilla de URI: las que llevan un identificador tienen
 * exactamente una variable, que se reemplaza por posición (el nombre no importa).
 */
@ConfigurationProperties("servicios.pagos.rutas")
public record RutasPagos(
        @DefaultValue(COBRO) String cobro,
        @DefaultValue(PAGOS_POR_COMPRA) String pagosPorCompra,
        @DefaultValue(REINTEGRO) String reintegro,
        @DefaultValue(REINTENTO_REINTEGRO) String reintentoReintegro,
        @DefaultValue(CONSULTA_REINTEGRO) String consultaReintegro) {

    public static final String COBRO = "/internal/pagos";
    public static final String PAGOS_POR_COMPRA = "/internal/pagos/compra/{purchaseId}";
    public static final String REINTEGRO = "/internal/pagos/{paymentId}/reintegros";
    public static final String REINTENTO_REINTEGRO = "/internal/reintegros/{refundId}/reintentar";
    public static final String CONSULTA_REINTEGRO = "/internal/reintegros/{refundId}";

    public RutasPagos {
        validar("cobro", cobro);
        validar("pagos-por-compra", pagosPorCompra);
        validar("reintegro", reintegro);
        validar("reintento-reintegro", reintentoReintegro);
        validar("consulta-reintegro", consultaReintegro);
    }

    /** Las rutas internas de Pagos. */
    public static RutasPagos internas() {
        return new RutasPagos(COBRO, PAGOS_POR_COMPRA, REINTEGRO, REINTENTO_REINTEGRO, CONSULTA_REINTEGRO);
    }

    private static void validar(String nombre, String ruta) {
        if (ruta == null || ruta.isBlank() || !ruta.startsWith("/")) {
            throw new IllegalArgumentException("La ruta de Pagos servicios.pagos.rutas." + nombre
                    + " debe empezar con '/': " + ruta);
        }
    }
}
