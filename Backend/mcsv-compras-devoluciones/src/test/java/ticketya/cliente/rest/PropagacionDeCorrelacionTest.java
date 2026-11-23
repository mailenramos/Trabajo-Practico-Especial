package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import ticketya.cliente.NotificacionesCliente.Aviso;
import ticketya.web.CorrelacionFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Todos los clientes REST (Reservas, Pagos, Eventos, Entradas, Notificaciones) propagan X-Correlation-Id. */
class PropagacionDeCorrelacionTest {

    private static final Duration DOS_SEGUNDOS = Duration.ofSeconds(2);
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

    /** Una llamada cualquiera de cada cliente; la respuesta (404/202) no importa, solo el pedido enviado. */
    static Stream<Arguments> llamadas() {
        Consumer<String> reservas = url -> new ReservasClienteRest(url, DOS_SEGUNDOS, DOS_SEGUNDOS)
                .obtenerReserva("r-1");
        Consumer<String> pagos = url -> new PagosClienteRest(url, DOS_SEGUNDOS, DOS_SEGUNDOS, RutasPagos.internas())
                .consultarReintegro("ref-1");
        Consumer<String> eventos = url -> new EventosClienteRest(url, DOS_SEGUNDOS, DOS_SEGUNDOS)
                .obtenerEvento("e-1");
        Consumer<String> entradas = url -> new EntradasClienteRest(url, "token", DOS_SEGUNDOS, DOS_SEGUNDOS)
                .listarPorCompra("c-1");
        Consumer<String> notificaciones = url -> new NotificacionesClienteRest(url, "token", DOS_SEGUNDOS, DOS_SEGUNDOS)
                .enviar(new Aviso("k-1", "COMPRA_CONFIRMADA", 1, Instant.parse("2026-11-20T12:00:00Z"), "c-1",
                        "cliente-1", "corr-aviso", JsonMapper.builder().build().readTree("{}")));
        return Stream.of(
                Arguments.of("Reservas", 404, reservas),
                Arguments.of("Pagos", 404, pagos),
                Arguments.of("Eventos", 404, eventos),
                Arguments.of("Entradas", 200, entradas),
                Arguments.of("Notificaciones", 202, notificaciones));
    }

    private String correlacionEnviada() {
        return servidor.encabezados.getFirst(CorrelacionFilter.ENCABEZADO);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("llamadas")
    void reenviaLaCorrelacionDelMdc(String servicio, int status, Consumer<String> llamada) {
        MDC.put(CorrelacionFilter.CLAVE_MDC, "corr-gateway-123");
        servidor.responder(status, status == 200 ? "[]" : "{}");

        llamada.accept(servidor.url());

        assertThat(correlacionEnviada()).as(servicio).isEqualTo("corr-gateway-123");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("llamadas")
    void sinCorrelacionEnElMdcGeneraUnaNuevaPorLlamada(String servicio, int status, Consumer<String> llamada) {
        servidor.responder(status, status == 200 ? "[]" : "{}");

        llamada.accept(servidor.url());
        String primera = correlacionEnviada();
        llamada.accept(servidor.url());
        String segunda = correlacionEnviada();

        assertThat(UUID.fromString(primera)).as(servicio).isNotNull();
        assertThat(UUID.fromString(segunda)).as(servicio).isNotNull();
        assertThat(segunda).as(servicio).isNotEqualTo(primera);
    }

    @Test
    void unaCorrelacionEnBlancoEnElMdcSeReemplazaPorUnaNueva() {
        MDC.put(CorrelacionFilter.CLAVE_MDC, "  ");
        servidor.responder(404, "{}");

        new EventosClienteRest(servidor.url(), DOS_SEGUNDOS, DOS_SEGUNDOS).obtenerEvento("e-1");

        assertThat(UUID.fromString(correlacionEnviada())).isNotNull();
    }

    /** Cliente sin interceptores propios: la correlación la pone la base, no cada cliente. */
    private static final class ClienteMinimo extends ClienteRest {
        ClienteMinimo(String url) {
            super("Prueba", url, DOS_SEGUNDOS, DOS_SEGUNDOS);
        }

        void llamar() {
            llamar("probar", () -> http.get().uri("/algo").retrieve().toBodilessEntity());
        }

        void llamarConCorrelacionPropia(String valor) {
            llamar("probar", () -> http.get().uri("/algo").header(CorrelacionFilter.ENCABEZADO, valor)
                    .retrieve().toBodilessEntity());
        }
    }

    @Test
    void laBaseLaAgregaACualquierCliente() {
        MDC.put(CorrelacionFilter.CLAVE_MDC, "corr-base");
        servidor.responder(200, "");

        new ClienteMinimo(servidor.url()).llamar();

        assertThat(correlacionEnviada()).isEqualTo("corr-base");
    }

    @Test
    void respetaUnaCorrelacionPuestaExplicitamentePorQuienLlama() {
        MDC.put(CorrelacionFilter.CLAVE_MDC, "corr-mdc");
        servidor.responder(200, "");

        new ClienteMinimo(servidor.url()).llamarConCorrelacionPropia("corr-explicita");

        assertThat(servidor.encabezados.get(CorrelacionFilter.ENCABEZADO)).containsExactly("corr-explicita");
    }
}
