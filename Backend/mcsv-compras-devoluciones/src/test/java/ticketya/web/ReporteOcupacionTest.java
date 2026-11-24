package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.EventosCliente.Sector;
import ticketya.cliente.ReservasCliente.Disponibilidad;
import ticketya.cliente.ReservasCliente.SectorDisponibilidad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /reportes/eventos/{eventoId}/ocupacion, con el inventario de Disponibilidad y reservas. */
class ReporteOcupacionTest extends PruebaReportes {

    private void inventario(SectorDisponibilidad... sectores) {
        reservas.definirDisponibilidad(new Disponibilidad(EVENTO_ID, List.of(sectores)));
    }

    @Test
    void informaLaOcupacionPorSectorYTotalConLasReservasAparte() throws Exception {
        inventario(new SectorDisponibilidad(PLATEA, 100, 40, 5), new SectorDisponibilidad(CAMPO, 300, 60, 0));

        comoOrganizador("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventarioInicializado").value(true))
                .andExpect(jsonPath("$.sectores[0].sectorId").value(PLATEA))
                .andExpect(jsonPath("$.sectores[0].sectorNombre").value("Platea"))
                .andExpect(jsonPath("$.sectores[0].capacidad").value(100))
                .andExpect(jsonPath("$.sectores[0].vendidos").value(40))
                .andExpect(jsonPath("$.sectores[0].reservasTemporales").value(5))
                .andExpect(jsonPath("$.sectores[0].porcentajeOcupacion").value(40.00))
                .andExpect(jsonPath("$.sectores[1].sectorNombre").value("Campo"))
                .andExpect(jsonPath("$.sectores[1].porcentajeOcupacion").value(20.00))
                .andExpect(jsonPath("$.totales.capacidad").value(400))
                .andExpect(jsonPath("$.totales.vendidos").value(100))
                .andExpect(jsonPath("$.totales.reservasTemporales").value(5))
                .andExpect(jsonPath("$.totales.porcentajeOcupacion").value(25.00));
    }

    @Test
    void elPorcentajeSeRedondeaADosDecimales() throws Exception {
        inventario(new SectorDisponibilidad(PLATEA, 3, 2, 0));

        comoAdmin("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].porcentajeOcupacion").value(66.67))
                .andExpect(jsonPath("$.totales.porcentajeOcupacion").value(66.67));
    }

    @Test
    void lasReservasTemporalesNoSumanAlPorcentaje() throws Exception {
        inventario(new SectorDisponibilidad(PLATEA, 10, 0, 10));

        comoOrganizador("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].reservasTemporales").value(10))
                .andExpect(jsonPath("$.sectores[0].porcentajeOcupacion").value(0.00));
    }

    @Test
    void unSectorConCapacidadCeroTieneOcupacionCero() throws Exception {
        inventario(new SectorDisponibilidad(PLATEA, 0, 0, 0), new SectorDisponibilidad(CAMPO, 10, 5, 0));

        comoOrganizador("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectores[0].porcentajeOcupacion").value(0.00))
                .andExpect(jsonPath("$.totales.porcentajeOcupacion").value(50.00));
    }

    @Test
    void unEventoSinInventarioRespondeInventarioNoInicializado() throws Exception {
        comoOrganizador("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventarioInicializado").value(false))
                .andExpect(jsonPath("$.sectores.length()").value(0))
                .andExpect(jsonPath("$.totales.capacidad").value(0))
                .andExpect(jsonPath("$.totales.vendidos").value(0))
                .andExpect(jsonPath("$.totales.porcentajeOcupacion").value(0.00));
    }

    @Test
    void conDisponibilidadCaidoResponde503SinDatosParciales() throws Exception {
        inventario(new SectorDisponibilidad(PLATEA, 100, 40, 5));
        reservas.setCaido(true);

        comoOrganizador("ocupacion")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.sectores").doesNotExist());

        reservas.setCaido(false);
        comoOrganizador("ocupacion").andExpect(status().isOk()).andExpect(jsonPath("$.totales.vendidos").value(40));
    }

    @Test
    void enUnEventoCanceladoMuestraElInventarioActual() throws Exception {
        eventos.agregar(new Evento(EVENTO_ID, "Recital de prueba", Instant.parse("2026-12-01T21:00:00Z"),
                "cancelado", List.of(new Sector(PLATEA, "Platea", new BigDecimal("15000.00"))), "RECITAL", ORGANIZADOR));
        inventario(new SectorDisponibilidad(PLATEA, 100, 0, 0));

        comoOrganizador("ocupacion")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoEvento").value("cancelado"))
                .andExpect(jsonPath("$.totales.vendidos").value(0))
                .andExpect(jsonPath("$.totales.porcentajeOcupacion").value(0.00));
    }
}
