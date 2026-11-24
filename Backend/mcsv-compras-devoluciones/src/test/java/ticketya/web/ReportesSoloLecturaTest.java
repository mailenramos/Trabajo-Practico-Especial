package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.ReservasCliente.Disponibilidad;
import ticketya.cliente.ReservasCliente.SectorDisponibilidad;
import ticketya.modelo.EstadoReintegro;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consultar los reportes no escribe nada y, sin operaciones intermedias, devuelve lo mismo. */
class ReportesSoloLecturaTest extends PruebaReportes {

    private static final List<String> REPORTES = List.of("ventas", "devoluciones", "recaudacion", "ocupacion");

    private List<Long> cantidades() {
        return List.of(compras.count(), solicitudes.count(), reintegros.count(), entradasDeReintegro.count(),
                avisos.count(), claves.count(), cancelaciones.count(), modificacionesDeEvento.count(),
                (long) reservas.confirmaciones(), (long) reservas.liberaciones(),
                (long) reservas.pedidosDeLiberacionPorDevolucion(), (long) entradas.llamadas());
    }

    /** El cuerpo sin generadoEn, que es lo único que cambia entre dos consultas. */
    private String sinFecha(String cuerpo) {
        return cuerpo.replaceAll("\"generadoEn\":\"[^\"]*\"", "");
    }

    @Test
    void consultarLosReportesNoModificaNadaYEsRepetible() throws Exception {
        UUID compra1 = compraConfirmada("cliente-1");
        devolucion(compra1, detalles(compra1, PLATEA).get(0), EstadoReintegro.EXITOSO);
        solicitudPendiente(compra1, detalles(compra1, CAMPO).get(0));
        UUID compra2 = compraConfirmada("cliente-2");
        cancelacion(compra2, EstadoReintegro.PENDIENTE, todas(compra2));
        reservas.definirDisponibilidad(new Disponibilidad(EVENTO_ID, List.of(
                new SectorDisponibilidad(PLATEA, 100, 3, 0), new SectorDisponibilidad(CAMPO, 300, 4, 0))));
        List<Long> antes = cantidades();

        for (String reporte : REPORTES) {
            String primera = comoOrganizador(reporte).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String segunda = comoAdmin(reporte).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(sinFecha(segunda)).as(reporte).isEqualTo(sinFecha(primera));
        }

        assertThat(cantidades()).as("compras, devoluciones, reintegros, avisos, claves e inventario").isEqualTo(antes);
    }

    @Test
    void unaConsultaFallidaPorEventosNoDejaEfectos() throws Exception {
        compraConfirmada("cliente-1");
        List<Long> antes = cantidades();
        String bien = comoOrganizador("ventas").andReturn().getResponse().getContentAsString();

        eventos.setCaido(true);
        comoOrganizador("ventas").andExpect(status().isServiceUnavailable());
        eventos.setCaido(false);

        String despues = comoOrganizador("ventas").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sinFecha(despues)).isEqualTo(sinFecha(bien));
        assertThat(cantidades()).isEqualTo(antes);
    }
}
