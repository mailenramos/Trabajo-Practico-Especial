package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.ReservasCliente.LugarDevuelto;
import ticketya.cliente.ServicioExternoException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Liberación por devolución: cuerpo enviado a Reservas. */
class ReservasClienteRestTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private ServidorDePrueba servidor;
    private ReservasClienteRest cliente;

    @BeforeEach
    void levantar() throws Exception {
        servidor = new ServidorDePrueba();
        servidor.responder(204, "");
        cliente = new ReservasClienteRest(servidor.url(), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    @Test
    void unLugarNumeradoSeLiberaConFilaYNumero() {
        cliente.liberarPorDevolucion("r-1", "dev-1", new LugarDevuelto("platea", 10, 1));

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/disponibilidad/reservas/r-1/liberacion");
        JsonNode cuerpo = json.readTree(servidor.cuerpoRecibido);
        assertThat(cuerpo.get("motivo").asString()).isEqualTo("DEVOLUCION_APROBADA");
        assertThat(cuerpo.get("devolucionId").asString()).isEqualTo("dev-1");
        assertThat(cuerpo.get("detalles")).hasSize(1);
        JsonNode lugar = cuerpo.get("detalles").get(0);
        assertThat(lugar.get("sectorId").asString()).isEqualTo("platea");
        assertThat(lugar.get("fila").isIntegralNumber()).isTrue();
        assertThat(lugar.get("fila").asInt()).isEqualTo(10);
        assertThat(lugar.get("numero").asInt()).isEqualTo(1);
        assertThat(lugar.has("cantidad")).isFalse();
    }

    @Test
    void unLugarNoNumeradoSeLiberaComoUnaUnidad() {
        cliente.liberarPorDevolucion("r-1", "dev-1", new LugarDevuelto("campo", null, null));

        JsonNode lugar = json.readTree(servidor.cuerpoRecibido).get("detalles").get(0);
        assertThat(lugar.get("sectorId").asString()).isEqualTo("campo");
        assertThat(lugar.get("cantidad").asInt()).isEqualTo(1);
        assertThat(lugar.has("fila")).isFalse();
    }

    @Test
    void laReservaSeLeeConLaFilaComoEntero() {
        servidor.responder(200, """
                {"reservaId":"r-1","clienteId":"c-1","eventoId":"e-1","estado":"VIGENTE",
                 "expiraEn":"2026-12-01T21:00:00Z",
                 "detalles":[{"sectorId":"platea","fila":10,"numero":1},{"sectorId":"campo","cantidad":2}]}""");

        var reserva = cliente.obtenerReserva("r-1").orElseThrow();

        assertThat(reserva.detalles().get(0).fila()).isEqualTo(10);
        assertThat(reserva.detalles().get(0).numero()).isEqualTo(1);
        assertThat(reserva.detalles().get(1).fila()).isNull();
        assertThat(reserva.detalles().get(1).cantidad()).isEqualTo(2);
    }

    @Test
    void unaReservaConFilaNoNumericaEsUnErrorDeContrato() {
        servidor.responder(200, """
                {"reservaId":"r-1","clienteId":"c-1","eventoId":"e-1","estado":"VIGENTE",
                 "detalles":[{"sectorId":"platea","fila":"A","numero":1}]}""");

        assertThatThrownBy(() -> cliente.obtenerReserva("r-1"))
                .isInstanceOf(ticketya.cliente.ContratoInvalidoException.class);
    }

    @Test
    void unConflictoEsUnaFallaDelPaso() {
        servidor.responder(409, "{\"error\":\"DEVOLUCION_CONFLICTO\"}");

        assertThatThrownBy(() -> cliente.liberarPorDevolucion("r-1", "dev-1", new LugarDevuelto("platea", 10, 1)))
                .isInstanceOf(ServicioExternoException.class);
    }
}
