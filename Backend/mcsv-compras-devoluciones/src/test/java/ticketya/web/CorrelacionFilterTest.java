package ticketya.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** X-Correlation-Id del pedido entrante en el MDC, mientras dura el pedido. */
class CorrelacionFilterTest {

    private final CorrelacionFilter filtro = new CorrelacionFilter();

    /** Ejecuta el filtro y devuelve la correlación que vio el controlador. */
    private String correlacionDuranteElPedido(String encabezado) throws Exception {
        MockHttpServletRequest pedido = new MockHttpServletRequest("POST", "/compras");
        if (encabezado != null) {
            pedido.addHeader(CorrelacionFilter.ENCABEZADO, encabezado);
        }
        AtomicReference<String> vista = new AtomicReference<>();
        FilterChain cadena = (req, res) -> vista.set(CorrelacionFilter.actual());
        filtro.doFilter(pedido, new MockHttpServletResponse(), cadena);
        return vista.get();
    }

    @Test
    void guardaLaCorrelacionEntranteMientrasDuraElPedido() throws Exception {
        assertThat(correlacionDuranteElPedido("corr-gateway-123")).isEqualTo("corr-gateway-123");
        assertThat(CorrelacionFilter.actual()).as("se borra al terminar el pedido").isNull();
    }

    @Test
    void sinCorrelacionEntranteGeneraUna() throws Exception {
        String generada = correlacionDuranteElPedido(null);

        assertThat(generada).isNotBlank();
        assertThat(UUID.fromString(generada)).isNotNull();
        assertThat(correlacionDuranteElPedido(null)).as("una nueva por pedido").isNotEqualTo(generada);
        assertThat(CorrelacionFilter.actual()).isNull();
    }

    @Test
    void unValorVacioOInvalidoSeReemplazaPorUnoGenerado() throws Exception {
        for (String invalido : new String[] {"", "   ", "con espacio", "x".repeat(101), "linea\nnueva"}) {
            String usada = correlacionDuranteElPedido(invalido);
            assertThat(usada).isNotEqualTo(invalido);
            assertThat(UUID.fromString(usada)).isNotNull();
        }
    }
}
