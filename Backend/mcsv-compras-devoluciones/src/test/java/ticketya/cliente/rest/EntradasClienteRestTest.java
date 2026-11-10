package ticketya.cliente.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.EntradasCliente.Entrada;
import ticketya.cliente.EntradasCliente.EntradaEmitida;
import ticketya.cliente.EntradasCliente.EventoEmision;
import ticketya.cliente.EntradasCliente.LineaEmision;
import ticketya.cliente.EntradasCliente.SolicitudEmision;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import ticketya.cliente.EntradasCliente.EstadoEntrada;
import ticketya.cliente.ServicioExternoException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cliente de Entradas con el contrato confirmado (la marcación por evento sigue siendo provisoria). */
class EntradasClienteRestTest {

    private ServidorDePrueba servidor;
    private EntradasClienteRest cliente;

    @BeforeEach
    void levantar() throws Exception {
        servidor = new ServidorDePrueba();
        cliente = new EntradasClienteRest(servidor.url(), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @AfterEach
    void apagar() {
        servidor.close();
    }

    @Test
    void consultaTitularEstadoYFechaDelEvento() {
        servidor.responder(200, """
                {"idEntrada":"e-1","idCliente":"cliente-1","estado":"VIGENTE",
                 "evento":{"id":"evento-1","comienzaEn":"2026-12-01T21:00:00Z"}}""");

        Entrada e = cliente.consultar("e-1").orElseThrow();

        assertThat(servidor.metodo).isEqualTo("GET");
        assertThat(servidor.ruta).isEqualTo("/internal/entradas/e-1");
        assertThat(e.idCliente()).isEqualTo("cliente-1");
        assertThat(e.estado()).isEqualTo(EstadoEntrada.VIGENTE);
        assertThat(e.comienzaEn()).isEqualTo(Instant.parse("2026-12-01T21:00:00Z"));
    }

    @Test
    void laConsultaLeeLaRespuestaCompletaConElSectorAnidado() {
        servidor.responder(200, """
                {"idEntrada":"e-1","codigoQr":"QR-1","estado":"VIGENTE",
                 "evento":{"id":"evento-1","nombre":"Recital","comienzaEn":"2026-12-01T21:00:00Z"},
                 "sector":{"id":"platea","nombre":"Platea"},
                 "idSector":"no-usar",
                 "fila":10,"numero":1,"idCompra":"compra-1","idCliente":"cliente-1"}""");

        Entrada e = cliente.consultar("e-1").orElseThrow();

        assertThat(e).isEqualTo(new Entrada("e-1", "cliente-1", "compra-1", EstadoEntrada.VIGENTE, "evento-1",
                Instant.parse("2026-12-01T21:00:00Z"), "platea", "Platea", 10, 1, "QR-1"));
    }

    @Test
    void laConsultaDeUnaEntradaNoNumeradaConservaFilaYNumeroNulos() {
        servidor.responder(200, """
                {"idEntrada":"e-2","estado":"VIGENTE","evento":{"id":"evento-1"},
                 "sector":{"id":"campo","nombre":"Campo"},"fila":null,"numero":null,"idCliente":"cliente-1"}""");

        Entrada e = cliente.consultar("e-2").orElseThrow();

        assertThat(e.sectorId()).isEqualTo("campo");
        assertThat(e.sectorNombre()).isEqualTo("Campo");
        assertThat(e.fila()).isNull();
        assertThat(e.numero()).isNull();
    }

    @Test
    void sinElSectorAnidadoNoSeTomaIdSector() {
        servidor.responder(200, "{\"idEntrada\":\"e-1\",\"idSector\":\"platea\",\"estado\":\"VIGENTE\"}");

        assertThat(cliente.consultar("e-1").orElseThrow().sectorId()).isNull();
    }

    @Test
    void elEstadoSeLeeSinDependerDelFormato() {
        servidor.responder(200, "{\"idCliente\":\"c\",\"estado\":\"en reintegro\"}");

        assertThat(cliente.consultar("e-1").orElseThrow().estado()).isEqualTo(EstadoEntrada.EN_REINTEGRO);
        assertThat(EstadoEntrada.de("Devuelta")).isEqualTo(EstadoEntrada.DEVUELTA);
        assertThat(EstadoEntrada.de("ANULADA")).isEqualTo(EstadoEntrada.DESCONOCIDO);
    }

    @Test
    void unaEntradaInexistenteDaVacio() {
        servidor.responder(404, "{\"error\":\"ENTRADA_NO_ENCONTRADA\"}");

        assertThat(cliente.consultar("e-1")).isEmpty();
    }

    @Test
    void unaEntradaInexistenteConElCodigoEnCodigoTambienDaVacio() {
        servidor.responder(404, "{\"codigo\":\"ENTRADA_NO_ENCONTRADA\"}");

        assertThat(cliente.consultar("e-1")).isEmpty();
    }

    @Test
    void un404SinElCodigoDeEntradaInexistenteEsUnaFallaDelServicio() {
        servidor.responder(404, "{\"error\":\"RUTA_NO_ENCONTRADA\"}");
        assertThatThrownBy(() -> cliente.consultar("e-1")).isInstanceOf(ServicioExternoException.class);

        servidor.responder(404, "");
        assertThatThrownBy(() -> cliente.consultar("e-1")).isInstanceOf(ServicioExternoException.class);
    }

    @Test
    void losCambiosDeEstadoUsanLasRutasDelContratoYUn2xxEsExito() {
        servidor.responder(204, "");
        assertThatCode(() -> cliente.pasarAEnReintegro("e-1")).doesNotThrowAnyException();
        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/entradas/e-1/reintegro");

        servidor.responder(200, "{}");
        assertThatCode(() -> cliente.pasarADevuelta("e-1")).doesNotThrowAnyException();
        assertThat(servidor.ruta).isEqualTo("/internal/entradas/e-1/devolucion");
    }

    private static SolicitudEmision solicitud() {
        return new SolicitudEmision("compra-1", "cliente-1",
                new EventoEmision("evento-1", "Recital", Instant.parse("2026-12-01T21:00:00Z")),
                List.of(new LineaEmision("platea", "Platea", 10, 1), new LineaEmision("campo", "Campo", null, null)));
    }

    @Test
    void laEmisionEnviaLaFilaComoEnteroYLeeIdSectorFilaYNumero() {
        servidor.responder(201, """
                {"idCompra":"compra-1","entradas":[
                  {"idEntrada":"e-1","idSector":"platea","fila":10,"numero":1,"codigoQr":"QR1"},
                  {"idEntrada":"e-2","idSector":"campo","fila":null,"numero":null,"codigoQr":"QR2"}]}""");

        List<EntradaEmitida> emitidas = cliente.emitir(solicitud());

        JsonNode pedido = JsonMapper.builder().build().readTree(servidor.cuerpoRecibido);
        assertThat(pedido.get("lugares").get(0).get("fila").isIntegralNumber()).isTrue();
        assertThat(pedido.get("lugares").get(0).get("fila").asInt()).isEqualTo(10);
        assertThat(emitidas).containsExactly(
                new EntradaEmitida("e-1", "platea", 10, 1, "QR1"),
                new EntradaEmitida("e-2", "campo", null, null, "QR2"));
    }

    @Test
    void elPedidoDeEmisionUsaExactamenteLosCamposDelContrato() {
        servidor.responder(201, "[]");

        cliente.emitir(solicitud());

        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/entradas/emision");
        JsonNode pedido = JsonMapper.builder().build().readTree(servidor.cuerpoRecibido);
        assertThat(nombres(pedido)).containsExactlyInAnyOrder("idCompra", "idCliente", "evento", "lugares");
        assertThat(pedido.get("idCompra").asString()).isEqualTo("compra-1");
        assertThat(pedido.get("idCliente").asString()).isEqualTo("cliente-1");
        JsonNode evento = pedido.get("evento");
        assertThat(nombres(evento)).containsExactlyInAnyOrder("id", "nombre", "comienzaEn");
        assertThat(evento.get("id").asString()).isEqualTo("evento-1");
        assertThat(evento.get("nombre").asString()).isEqualTo("Recital");
        assertThat(Instant.parse(evento.get("comienzaEn").asString())).isEqualTo(Instant.parse("2026-12-01T21:00:00Z"));
        JsonNode lugar = pedido.get("lugares").get(0);
        assertThat(nombres(lugar)).containsExactlyInAnyOrder("idSector", "nombreSector", "fila", "numero");
        assertThat(lugar.get("idSector").asString()).isEqualTo("platea");
        assertThat(lugar.get("nombreSector").asString()).isEqualTo("Platea");
        assertThat(lugar.get("fila").isIntegralNumber()).isTrue();
        assertThat(lugar.get("numero").isIntegralNumber()).isTrue();
        assertThat(lugar.get("numero").asInt()).isEqualTo(1);
    }

    @Test
    void unSectorNoNumeradoMandaUnaLineaPorLugarConFilaYNumeroEnNull() {
        servidor.responder(201, """
                [{"idEntrada":"e-1","idSector":"campo","fila":null,"numero":null,"codigoQr":"QR1"},
                 {"idEntrada":"e-2","idSector":"campo","fila":null,"numero":null,"codigoQr":"QR2"}]""");
        SolicitudEmision noNumerado = new SolicitudEmision("compra-1", "cliente-1",
                new EventoEmision("evento-1", "Recital", Instant.parse("2026-12-01T21:00:00Z")),
                List.of(new LineaEmision("campo", "Campo", null, null), new LineaEmision("campo", "Campo", null, null)));

        List<EntradaEmitida> emitidas = cliente.emitir(noNumerado);

        JsonNode lugares = JsonMapper.builder().build().readTree(servidor.cuerpoRecibido).get("lugares");
        assertThat(lugares).hasSize(2);
        for (JsonNode lugar : lugares) {
            assertThat(lugar.has("fila")).as("fila presente como null explícito").isTrue();
            assertThat(lugar.get("fila").isNull()).isTrue();
            assertThat(lugar.has("numero")).as("numero presente como null explícito").isTrue();
            assertThat(lugar.get("numero").isNull()).isTrue();
        }
        assertThat(emitidas).containsExactly(
                new EntradaEmitida("e-1", "campo", null, null, "QR1"),
                new EntradaEmitida("e-2", "campo", null, null, "QR2"));
    }

    private static List<String> nombres(JsonNode nodo) {
        List<String> nombres = new java.util.ArrayList<>();
        nodo.propertyNames().forEach(nombres::add);
        return nombres;
    }

    @Test
    void sinIdSectorSeUsaSectorIdYUnaFilaEnTextoNumericoSeAcepta() {
        servidor.responder(201, "[{\"idEntrada\":\"e-1\",\"sectorId\":\"platea\",\"fila\":\"10\",\"numero\":1}]");

        assertThat(cliente.emitir(solicitud())).containsExactly(new EntradaEmitida("e-1", "platea", 10, 1, null));
    }

    @Test
    void unaFilaNoNumericaEnLaEmisionEsUnErrorDeContrato() {
        servidor.responder(201, "[{\"idEntrada\":\"e-1\",\"idSector\":\"platea\",\"fila\":\"A\",\"numero\":1}]");

        assertThatThrownBy(() -> cliente.emitir(solicitud()))
                .isInstanceOf(ContratoInvalidoException.class)
                .hasMessageContaining("fila");
    }

    @Test
    void laMarcacionPorEventoUsaLaRutaPropuestaYLeeEntradasMarcadas() {
        servidor.responder(200, "{\"idEvento\":\"evento-1\",\"entradasMarcadas\":4}");

        assertThat(cliente.marcarEntradasDelEvento("evento-1")).isEqualTo(4);
        assertThat(servidor.metodo).isEqualTo("POST");
        assertThat(servidor.ruta).isEqualTo("/internal/eventos/evento-1/entradas/reintegro");
    }

    @Test
    void laMarcacionRepetidaRespondeCeroYSinElCampoTambienEsCero() {
        servidor.responder(200, "{\"entradasMarcadas\":0}");
        assertThat(cliente.marcarEntradasDelEvento("evento-1")).isZero();

        servidor.responder(204, "");
        assertThat(cliente.marcarEntradasDelEvento("evento-1")).isZero();
    }

    @Test
    void unaFallaEnLaMarcacionEsUnaFallaDelServicio() {
        servidor.responder(500, "{}");

        assertThatThrownBy(() -> cliente.marcarEntradasDelEvento("evento-1"))
                .isInstanceOf(ServicioExternoException.class);
    }

    @Test
    void unCambioDeEstadoRepetidoQueResponde200EsExito() {
        servidor.responder(200, "{\"idEntrada\":\"e-1\",\"estado\":\"EN_REINTEGRO\"}");

        assertThatCode(() -> cliente.pasarAEnReintegro("e-1")).doesNotThrowAnyException();
        assertThatCode(() -> cliente.pasarAEnReintegro("e-1")).doesNotThrowAnyException();
    }

    @Test
    void unaTransicionInvalidaEsUnConflictoDeEstado() {
        servidor.responder(409, "{\"status\":409,\"error\":\"TRANSICION_INVALIDA\",\"mensaje\":\"x\"}");

        assertThatThrownBy(() -> cliente.pasarAEnReintegro("e-1"))
                .isInstanceOf(EstadoEntradaConflictoException.class)
                .hasMessageContaining("Conflicto de estado")
                .hasMessageContaining("TRANSICION_INVALIDA")
                .satisfies(e -> assertThat(((EstadoEntradaConflictoException) e).getCodigo())
                        .isEqualTo("TRANSICION_INVALIDA"));

        servidor.responder(409, "{\"codigo\":\"TRANSICION_INVALIDA\"}");
        assertThatThrownBy(() -> cliente.pasarADevuelta("e-1"))
                .isInstanceOf(EstadoEntradaConflictoException.class)
                .hasMessageContaining("TRANSICION_INVALIDA");
    }

    @Test
    void unaFallaDelServicioEnUnCambioDeEstadoNoEsUnConflicto() {
        servidor.responder(500, "{}");

        assertThatThrownBy(() -> cliente.pasarAEnReintegro("e-1"))
                .isInstanceOf(ServicioExternoException.class)
                .isNotInstanceOf(EstadoEntradaConflictoException.class);
        assertThatThrownBy(() -> cliente.pasarADevuelta("e-1"))
                .isInstanceOf(ServicioExternoException.class)
                .isNotInstanceOf(EstadoEntradaConflictoException.class);
    }
}
