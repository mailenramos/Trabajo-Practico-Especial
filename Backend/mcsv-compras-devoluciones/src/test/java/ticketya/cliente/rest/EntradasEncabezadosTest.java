package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.ServicioExternoException;
import ticketya.web.CorrelacionFilter;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** X-Service-Token y X-Correlation-Id en las llamadas a Entradas. */
class EntradasEncabezadosTest {

    private static final String TOKEN = "token-de-prueba";
    private ServidorDePrueba servidor;

    @BeforeEach
    void levantar() throws Exception {
        servidor = new ServidorDePrueba();
    }

    @AfterEach
    void apagar() {
        servidor.close();
        MDC.clear();
    }

    private EntradasClienteRest cliente(String token) {
        return new EntradasClienteRest(servidor.url(), token, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void todasLasLlamadasLlevanElTokenDeServicio() {
        EntradasClienteRest cliente = cliente(TOKEN);

        servidor.responder(200, "{\"idCliente\":\"c\",\"estado\":\"VIGENTE\"}");
        cliente.consultar("e-1");
        assertThat(servidor.encabezados.getFirst("X-Service-Token")).isEqualTo(TOKEN);

        servidor.responder(200, "{}");
        cliente.pasarAEnReintegro("e-1");
        assertThat(servidor.encabezados.getFirst("X-Service-Token")).isEqualTo(TOKEN);

        servidor.responder(200, "{\"entradasMarcadas\":0,\"cerrado\":true}");
        cliente.marcarEntradasDelEvento("evento-1");
        assertThat(servidor.encabezados.getFirst("X-Service-Token")).isEqualTo(TOKEN);
    }

    @Test
    void sinTokenConfiguradoNoSeEnviaYEl403EsUnaFallaTecnica() {
        for (String sinToken : new String[] {null, "", "   "}) {
            EntradasClienteRest cliente = cliente(sinToken);
            servidor.responder(403, "{\"error\":\"SERVICIO_NO_AUTORIZADO\"}");

            assertThatThrownBy(() -> cliente.pasarAEnReintegro("e-1"))
                    .isInstanceOf(ServicioExternoException.class)
                    .isNotInstanceOf(EstadoEntradaConflictoException.class)
                    .hasMessageContaining("403");
            assertThat(servidor.encabezados.containsKey("X-Service-Token")).isFalse();
        }
    }

    @Test
    void reenviaLaCorrelacionDelPedidoEnCurso() {
        MDC.put(CorrelacionFilter.CLAVE_MDC, "corr-gateway-123");
        servidor.responder(200, "{\"idCliente\":\"c\",\"estado\":\"VIGENTE\"}");

        cliente(TOKEN).consultar("e-1");

        assertThat(servidor.encabezados.getFirst("X-Correlation-Id")).isEqualTo("corr-gateway-123");
    }

    @Test
    void sinCorrelacionEntranteGeneraUnaNuevaEnCadaLlamada() {
        EntradasClienteRest cliente = cliente(TOKEN);
        servidor.responder(200, "{\"idCliente\":\"c\",\"estado\":\"VIGENTE\"}");

        cliente.consultar("e-1");
        String primera = servidor.encabezados.getFirst("X-Correlation-Id");
        cliente.consultar("e-1");
        String segunda = servidor.encabezados.getFirst("X-Correlation-Id");

        assertThat(UUID.fromString(primera)).isNotNull();
        assertThat(UUID.fromString(segunda)).isNotNull();
        assertThat(segunda).isNotEqualTo(primera);
    }
}
