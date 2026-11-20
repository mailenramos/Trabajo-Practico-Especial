package com.ticketya.msvc.entradas.clientes;

import com.ticketya.msvc.entradas.config.EntradasProperties;
import com.ticketya.msvc.entradas.exception.ServicioNoDisponibleException;
import com.ticketya.msvc.entradas.seguridad.CorrelationIdFilter;
import com.ticketya.msvc.entradas.seguridad.ServiceTokenFilter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GET /internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion
 * → siempre 200 {eventoId, usuarioId, autorizado}. Un evento inexistente da autorizado=false.
 * Cualquier 4xx/5xx, timeout o falla de conexión es una falla real: se rechaza con 503.
 */
@Component
public class AutorizacionAccesoClienteRest implements AutorizacionAccesoCliente {

    static final String RUTA = "/internal/eventos/{eventoId}/personal-acceso/{usuarioId}/autorizacion";

    private final RestClient ms1;
    private final String serviceToken;

    public AutorizacionAccesoClienteRest(RestClient ms1RestClient, EntradasProperties propiedades) {
        this.ms1 = ms1RestClient;
        this.serviceToken = propiedades.seguridad().serviceToken();
    }

    @Override
    public boolean estaAutorizado(String idEvento, String idUsuario) {
        Respuesta respuesta;
        try {
            respuesta = ms1.get()
                    .uri(RUTA, idEvento, idUsuario)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        h.set(CorrelationIdFilter.ENCABEZADO, CorrelationIdFilter.actual());
                        if (serviceToken != null && !serviceToken.isBlank()) {
                            h.set(ServiceTokenFilter.ENCABEZADO, serviceToken);
                        }
                    })
                    .retrieve()
                    .body(Respuesta.class);
        } catch (RestClientException ex) {
            throw noDisponible(ex);
        }
        if (respuesta == null || respuesta.autorizado() == null) {
            throw noDisponible(null);
        }
        return respuesta.autorizado();
    }

    private static ServicioNoDisponibleException noDisponible(Throwable causa) {
        return new ServicioNoDisponibleException("AUTORIZACION_NO_DISPONIBLE",
                "No se pudo verificar la asignación del personal al evento; reintentá la validación", causa);
    }

    record Respuesta(String eventoId, String usuarioId, Boolean autorizado) {
    }
}
