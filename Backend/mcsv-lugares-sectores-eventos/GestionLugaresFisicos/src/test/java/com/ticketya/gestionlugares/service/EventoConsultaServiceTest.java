package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

class EventoConsultaServiceTest {
    private EventoConsultaService service;

    @BeforeEach
    void crearService() {
        service = new EventoConsultaService(mock(MongoTemplate.class), mock(EventoRepository.class),
                mock(LugarRepository.class), mock(com.ticketya.gestionlugares.reservas.ReservasClient.class));
    }

    @Test
    void rechazaRolNoAdmitidoAntesDeConsultarMongo() {
        assertThrows(ForbiddenException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, null, "u1", "ADMINISTRADOR"));
    }

    @Test
    void rechazaIdentidadAusente() {
        assertThrows(UnauthorizedException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, null, null, "CLIENTE"));
        assertThrows(UnauthorizedException.class, () -> service.detalle("evento-id", "u1", " "));
    }

    @Test
    void rechazaPaginacionYOrdenInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, "-1", null, "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, "101", "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                "popularidad", null, null, null, null, "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, "sideways", null, null, null, "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, "0", "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, "one", null, "u1", "CLIENTE"));
    }

    @Test
    void rechazaFechaNoUtcYRangoInvertido() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "2027-01-01T00:00:00+00:00", null, null, null, null, null, null, "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "not-a-date", null, null, null, null, null, null, "u1", "CLIENTE"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "2027-02-01T00:00:00Z", "2027-01-01T00:00:00Z", null, null, null, null, null, "u1", "CLIENTE"));
    }
}
