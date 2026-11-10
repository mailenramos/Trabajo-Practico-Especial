package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ticketya.PruebaIntegracion;
import ticketya.cliente.ReservasCliente.EstadoReserva;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C-1: iniciar una compra desde una reserva (y P-3: solo el titular compra su reserva). */
class IniciarCompraTest extends PruebaIntegracion {

    @Test
    void compraIniciadaConReservaPropiaVigente() throws Exception {
        String reservaId = reservaVigente("cliente-1");

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.reservaId").value(reservaId))
                .andExpect(jsonPath("$.evento.nombre").value("Recital de prueba"))
                .andExpect(jsonPath("$.total").value(46001.00))
                .andExpect(jsonPath("$.lugares.length()").value(4));

        Compra compra = compras.findByReservaId(reservaId).orElseThrow();
        assertThat(compra.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(compra.getClienteId()).isEqualTo("cliente-1");
        assertThat(compra.getTotal()).isEqualByComparingTo("46001.00");
        assertThat(compra.getTotal().scale()).isEqualTo(2);
    }

    @Test
    void cadaLugarEsUnaFilaDelDetalleConSuPrecioUnitario() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        iniciarCompra("cliente-1", reservaId).andExpect(status().isCreated());

        List<DetalleCompra> detalle = compraConDetalle(reservaId);
        assertThat(detalle).hasSize(4);
        assertThat(detalle).filteredOn(d -> d.getSectorId().equals(PLATEA))
                .extracting(DetalleCompra::getFila, DetalleCompra::getNumero)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(10, 1),
                        org.assertj.core.groups.Tuple.tuple(10, 2));
        assertThat(detalle).filteredOn(d -> d.getSectorId().equals(CAMPO))
                .hasSize(2)
                .allSatisfy(d -> {
                    assertThat(d.getFila()).isNull();
                    assertThat(d.getNumero()).isNull();
                    assertThat(d.getSectorNombre()).isEqualTo("Campo");
                    assertThat(d.getPrecioUnitario()).isEqualByComparingTo(new BigDecimal("8000.50"));
                    assertThat(d.getEntradaId()).isNull();
                });
    }

    @Test
    void reservaDeOtroClienteSeRechazaYNoRegistraCompra() throws Exception {
        String reservaId = reservaVigente("cliente-1");

        iniciarCompra("cliente-2", reservaId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("RESERVA_AJENA"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.mensaje").exists());

        assertThat(compras.count()).isZero();
    }

    @Test
    void reservaInexistenteDaReservaNoEncontrada() throws Exception {
        iniciarCompra("cliente-1", "no-existe")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESERVA_NO_ENCONTRADA"));

        assertThat(compras.count()).isZero();
    }

    @Test
    void reservaNoVigenteSeRechaza() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        reservas.cambiarEstado(reservaId, EstadoReserva.LIBERADA);

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESERVA_NO_VIGENTE"));

        assertThat(compras.count()).isZero();
    }

    @Test
    void repetirElPedidoConLaCompraPendienteDevuelveEsaCompraSinConsultarAEventos() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        String primera = iniciarCompra("cliente-1", reservaId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = compras.findByReservaId(reservaId).orElseThrow().getId().toString();
        eventos.setCaido(true);

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.total").value(46001.00))
                .andExpect(jsonPath("$.lugares.length()").value(4));

        assertThat(compras.count()).isEqualTo(1);
        assertThat(primera).contains(id);
    }

    @Test
    void siLaCompraDeLaReservaYaSeResolvioSeRechaza() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        iniciarCompra("cliente-1", reservaId).andExpect(status().isCreated());
        Compra compra = compras.findByReservaId(reservaId).orElseThrow();
        compras.cambiarEstado(compra.getId(), EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA);

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("COMPRA_YA_INICIADA"));

        assertThat(compras.count()).isEqualTo(1);
    }

    @Test
    void otroClienteNoRecibeLaCompraPendienteDeUnaReservaAjena() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        iniciarCompra("cliente-1", reservaId).andExpect(status().isCreated());

        iniciarCompra("cliente-2", reservaId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("RESERVA_AJENA"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void sinEncabezadoDeUsuarioDa401() throws Exception {
        mvc.perform(post("/compras").contentType(MediaType.APPLICATION_JSON).content("{\"reservaId\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("USUARIO_NO_IDENTIFICADO"));
    }

    @Test
    void sinReservaIdDaErrorDeValidacionConCampos() throws Exception {
        mvc.perform(post("/compras").header("X-User-Id", "cliente-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.reservaId").exists());
    }

    @Test
    void siEventosNoRespondeDa503SinRegistrarCompra() throws Exception {
        String reservaId = reservaVigente("cliente-1");
        eventos.setCaido(true);

        iniciarCompra("cliente-1", reservaId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"));

        assertThat(compras.count()).isZero();
    }

    private List<DetalleCompra> compraConDetalle(String reservaId) {
        Compra compra = compras.findByReservaId(reservaId).orElseThrow();
        return compras.buscarConDetalle(compra.getId()).orElseThrow().getDetalle();
    }
}
