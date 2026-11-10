package ticketya.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.ServicioExternoException;

import static org.assertj.core.api.Assertions.assertThat;

/** Un conflicto de estado en Entradas no se informa como una caída del servicio. */
class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador = new ManejadorDeErrores();

    @Test
    void unConflictoDeEstadoEnEntradasEsUn409ConSuCodigo() {
        ResponseEntity<ErrorRespuesta> r = manejador.conflictoDeEstadoExterno(
                new EstadoEntradaConflictoException("e-1", "pasar a devuelta", "TRANSICION_INVALIDA", null));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().status()).isEqualTo(409);
        assertThat(r.getBody().error()).isEqualTo("CONFLICTO_DE_ESTADO_EXTERNO");
        assertThat(r.getBody().mensaje()).contains("e-1").contains("transición inválida");
    }

    @Test
    void unaCaidaSigueSiendoServicioNoDisponible() {
        ResponseEntity<ErrorRespuesta> r = manejador.servicioExterno(
                new ServicioExternoException("Entradas", "Entradas no responde"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getBody().error()).isEqualTo("SERVICIO_NO_DISPONIBLE");
    }
}
