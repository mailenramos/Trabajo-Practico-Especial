package ticketya.cliente.rest;

import org.junit.jupiter.api.Test;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.ServicioExternoException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Distinción entre un servicio que no responde y uno que responde fuera de contrato. */
class ClienteRestTest {

    private static EventosClienteRest clienteDe(String url) {
        return new EventosClienteRest(url, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void unCuerpoQueNoSePuedeLeerEsUnErrorDeContrato() throws Exception {
        try (ServidorDePrueba servidor = new ServidorDePrueba()) {
            servidor.responder(200, "{\"eventoId\":\"e-1\",\"fechaInicio\":\"no es una fecha\"}");

            assertThatThrownBy(() -> clienteDe(servidor.url()).obtenerEvento("e-1"))
                    .isInstanceOf(ContratoInvalidoException.class)
                    .hasMessageContaining("formato inesperado");
        }
    }

    @Test
    void unJsonMalFormadoTambienEsUnErrorDeContrato() throws Exception {
        try (ServidorDePrueba servidor = new ServidorDePrueba()) {
            servidor.responder(200, "{\"eventoId\":");

            assertThatThrownBy(() -> clienteDe(servidor.url()).obtenerEvento("e-1"))
                    .isInstanceOf(ContratoInvalidoException.class);
        }
    }

    /** Cliente mínimo que devuelve el código de error leído de la respuesta. */
    private static final class ClienteDeCodigo extends ClienteRest {
        ClienteDeCodigo(String url) {
            super("Prueba", url, Duration.ofSeconds(1), Duration.ofSeconds(1));
        }

        String codigo() {
            return llamar("probar", () -> {
                http.get().uri("/algo").retrieve().toBodilessEntity();
                return "SIN_ERROR";
            }, (status, e) -> String.valueOf(codigoDeError(e)));
        }
    }

    @Test
    void elCodigoDeErrorSeLeeDeErrorODeCodigo() throws Exception {
        try (ServidorDePrueba servidor = new ServidorDePrueba()) {
            ClienteDeCodigo cliente = new ClienteDeCodigo(servidor.url());

            servidor.responder(404, "{\"status\":404,\"error\":\"ENTRADA_NO_ENCONTRADA\",\"mensaje\":\"x\"}");
            assertThat(cliente.codigo()).isEqualTo("ENTRADA_NO_ENCONTRADA");

            servidor.responder(404, "{\"codigo\":\"ENTRADA_NO_ENCONTRADA\",\"mensaje\":\"x\"}");
            assertThat(cliente.codigo()).isEqualTo("ENTRADA_NO_ENCONTRADA");

            servidor.responder(409, "{\"error\":\"TRANSICION_INVALIDA\",\"codigo\":\"OTRO\"}");
            assertThat(cliente.codigo()).as("si vienen los dos, manda error").isEqualTo("TRANSICION_INVALIDA");

            servidor.responder(409, "{\"error\":\"\",\"codigo\":\"TRANSICION_INVALIDA\"}");
            assertThat(cliente.codigo()).isEqualTo("TRANSICION_INVALIDA");

            servidor.responder(500, "{\"mensaje\":\"sin código\"}");
            assertThat(cliente.codigo()).isEqualTo("null");

            servidor.responder(500, "no es json");
            assertThat(cliente.codigo()).isEqualTo("null");
        }
    }

    @Test
    void sinRespuestaSigueSiendoServicioNoDisponibleYNoErrorDeContrato() throws Exception {
        String url;
        try (ServidorDePrueba servidor = new ServidorDePrueba()) {
            url = servidor.url();
        }

        assertThatThrownBy(() -> clienteDe(url).obtenerEvento("e-1"))
                .isInstanceOf(ServicioExternoException.class)
                .satisfies(e -> assertThat(e).isNotInstanceOf(ContratoInvalidoException.class));
    }
}
