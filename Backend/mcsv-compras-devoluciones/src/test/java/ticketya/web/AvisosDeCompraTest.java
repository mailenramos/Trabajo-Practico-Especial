package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.PruebaIntegracion;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.modelo.AvisoPendiente;
import ticketya.modelo.EstadoAviso;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.TipoAviso;
import ticketya.servicio.AvisoService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Avisos de compra confirmada y entradas disponibles, guardados en la transacción del pago. */
class AvisosDeCompraTest extends PruebaIntegracion {

    private static final String CLIENTE = "cliente-1";

    @Autowired AvisoService avisoService;
    @Autowired TransactionTemplate transaccion;
    private final JsonMapper json = JsonMapper.builder().build();

    private AvisoPendiente aviso(TipoAviso tipo, String acontecimientoId) {
        return avisos.findByAcontecimientoIdAndTipoAndUsuarioId(acontecimientoId, tipo, CLIENTE).orElseThrow();
    }

    @Test
    void alConfirmarseLaCompraQuedanLosDosAvisosPendientes() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        assertThat(compras.findById(compraId).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        AvisoPendiente compra = aviso(TipoAviso.COMPRA_CONFIRMADA, "compra-confirmada-" + compraId);
        assertThat(compra.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
        assertThat(compra.getRecursoId()).isEqualTo(compraId.toString());
        JsonNode datos = json.readTree(compra.getDatos());
        assertThat(datos.get("evento").asString()).isEqualTo("Recital de prueba");
        // 2026-12-01T21:00:00Z en Buenos Aires (UTC-3)
        assertThat(datos.get("fecha").asString()).isEqualTo("01/12/2026 18:00");
        assertThat(datos.get("cantidad").asInt()).isEqualTo(4);
        assertThat(datos.get("total").asString()).isEqualTo("46001.00");

        AvisoPendiente entradas = aviso(TipoAviso.ENTRADAS_DISPONIBLES, "entradas-disponibles-" + compraId);
        assertThat(entradas.getEstado()).isEqualTo(EstadoAviso.PENDIENTE);
        JsonNode datosEntradas = json.readTree(entradas.getDatos());
        assertThat(datosEntradas.get("evento").asString()).isEqualTo("Recital de prueba");
        assertThat(datosEntradas.get("fecha").asString()).isEqualTo("01/12/2026 18:00");
        assertThat(avisos.count()).isEqualTo(2);
    }

    @Test
    void repetirElPagoNoDuplicaLosAvisos() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());
        pagar(CLIENTE, compraId, "clave-2", "CREDIT_CARD").andExpect(status().isOk());

        assertThat(avisos.count()).isEqualTo(2);
    }

    @Test
    void unPagoRechazadoNoGeneraAvisos() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        pagos.setDecision(EstadoPago.RECHAZADO);

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD").andExpect(status().isOk());

        assertThat(compras.findById(compraId).orElseThrow().getEstado()).isEqualTo(EstadoCompra.FALLIDA);
        assertThat(avisos.count()).isZero();
    }

    @Test
    void unaCompraQueNoSeLlegaAConfirmarNoGeneraAvisos() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);
        entradas.fallarProximas(10); // la emisión falla: la compra queda pendiente para retomarla

        pagar(CLIENTE, compraId, "clave-1", "CREDIT_CARD");

        assertThat(compras.findById(compraId).orElseThrow().getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(avisos.count()).isZero();
    }

    @Test
    void repetirElAvisoDentroDeLaMismaOperacionNoLaRevierte() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        transaccion.executeWithoutResult(t -> {
            compras.cambiarEstado(compraId, EstadoCompra.PENDIENTE, EstadoCompra.CONFIRMADA);
            avisoService.compraConfirmada(compraId);
            avisoService.compraConfirmada(compraId); // mismo acontecimiento, tipo y destinatario
        });

        // La transacción se confirmó: el cambio de estado quedó y hay un aviso de cada tipo
        assertThat(compras.findById(compraId).orElseThrow().getEstado()).isEqualTo(EstadoCompra.CONFIRMADA);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.COMPRA_CONFIRMADA)).hasSize(1);
        assertThat(avisos.findByTipoOrderByIdAsc(TipoAviso.ENTRADAS_DISPONIBLES)).hasSize(1);
    }

    @Test
    void siLaTransaccionDeLaOperacionSeRevierteNoQuedaNingunAviso() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        assertThatThrownBy(() -> transaccion.executeWithoutResult(t -> {
            avisoService.compraConfirmada(compraId);
            throw new IllegalStateException("falla la operación de negocio");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(avisos.count()).isZero();
    }

    @Test
    void fueraDeUnaTransaccionNoSeGeneraElAviso() throws Exception {
        UUID compraId = compraPendiente(CLIENTE);

        assertThatThrownBy(() -> avisoService.compraConfirmada(compraId))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(avisos.count()).isZero();
    }
}
