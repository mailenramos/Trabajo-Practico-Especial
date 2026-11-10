package ticketya.servicio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ticketya.PruebaIntegracion;
import ticketya.cliente.ReservasCliente.Reserva;
import ticketya.cliente.ReservasCliente.EstadoReserva;
import ticketya.error.ApiException;
import ticketya.modelo.Compra;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C-2: verificar la reserva antes de pedir el cobro. */
class VerificarReservaTest extends PruebaIntegracion {

    @Autowired
    PagoService pagoService;

    @Test
    void conLaReservaVigenteSeContinuaConElCobro() throws Exception {
        UUID id = compraPendiente("cliente-1");

        assertThatCode(() -> pagoService.verificarReservaAntesDeCobrar(compra(id))).doesNotThrowAnyException();
    }

    @Test
    void conLaReservaLiberadaNoSeCobraYSeAvisaQueHayQueReservarDeNuevo() throws Exception {
        UUID id = compraPendiente("cliente-1");
        Compra compra = compra(id);
        reservas.cambiarEstado(compra.getReservaId(), EstadoReserva.LIBERADA);

        assertThatThrownBy(() -> pagoService.verificarReservaAntesDeCobrar(compra))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo("RESERVA_NO_VIGENTE");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("reservar de nuevo");
                });
    }

    @Test
    void conLaReservaVencidaPorTiempoNoSeCobra() throws Exception {
        UUID id = compraPendiente("cliente-1");
        Compra compra = compra(id);
        Reserva r = reservas.reserva(compra.getReservaId());
        reservas.agregar(new Reserva(r.reservaId(), r.clienteId(), r.eventoId(), EstadoReserva.VIGENTE,
                Instant.now().minusSeconds(1), r.detalles()));

        assertThatThrownBy(() -> pagoService.verificarReservaAntesDeCobrar(compra))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo("RESERVA_NO_VIGENTE"));
    }

    private Compra compra(UUID id) {
        return compras.findById(id).orElseThrow();
    }
}
