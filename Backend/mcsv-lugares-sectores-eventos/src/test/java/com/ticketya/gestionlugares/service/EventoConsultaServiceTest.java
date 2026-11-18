package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;

class EventoConsultaServiceTest {
    private static final String EVENTO_ID = new ObjectId().toHexString();

    private MongoTemplate mongoTemplate;
    private EventoRepository eventoRepository;
    private LugarRepository lugarRepository;
    private EventoConsultaService service;

    @BeforeEach
    void crearService() {
        mongoTemplate = mock(MongoTemplate.class);
        eventoRepository = mock(EventoRepository.class);
        lugarRepository = mock(LugarRepository.class);
        service = new EventoConsultaService(mongoTemplate, eventoRepository, lugarRepository, mock(ReservasClient.class));
    }

    @Test
    void busquedaPublicaNoRequiereIdentidad() {
        when(mongoTemplate.count(any(Query.class), eq(Evento.class))).thenReturn(0L);
        when(mongoTemplate.find(any(Query.class), eq(Evento.class))).thenReturn(List.of());

        var response = service.buscar(null, null, null, null, null, null,
                null, null, null, null, null);

        assertEquals(0, response.totalElementos());
        assertEquals(0, response.totalPaginas());
    }

    @Test
    void borradorSinIdentidadCompletaResponde401YOcultaAUsuariosSinPermiso() {
        Evento draft = draft();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(draft));

        assertThrows(UnauthorizedException.class, () -> service.detalle(EVENTO_ID, null, null));
        assertThrows(UnauthorizedException.class, () -> service.detalle(EVENTO_ID, "owner-1", " , "));
        assertThrows(ResourceNotFoundException.class,
                () -> service.detalle(EVENTO_ID, "other-1", "CLIENTE"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.detalle(EVENTO_ID, "owner-1", "CLIENTE"));
    }

    @Test
    void adminPuedeConsultarBorradorConRolEnLista() {
        Evento draft = draft();
        when(eventoRepository.findById(EVENTO_ID)).thenReturn(Optional.of(draft));
        when(lugarRepository.findById("place-1"))
                .thenReturn(Optional.of(new Lugar("place-1", "Sala", "Calle 1", "CABA", -34, -58, List.of())));

        var detail = service.detalle(EVENTO_ID, "admin-1", "CLIENTE, ADMIN");

        assertEquals("borrador", detail.estado());
    }

    @Test
    void rechazaPaginacionYOrdenInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, "-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, "101"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                "popularidad", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, "sideways", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, null, "0"));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null, null, null,
                null, null, null, "one", null));
    }

    @Test
    void rechazaFechaNoUtcYRangoInvertido() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "2027-01-01T00:00:00+00:00", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "not-a-date", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null, null, null, null,
                "2027-02-01T00:00:00Z", "2027-01-01T00:00:00Z", null, null, null, null, null));
    }

    private static Evento draft() {
        Evento event = Evento.borrador("owner-1", "place-1", "CABA", "Show", "Description",
                "MUSICA", List.of("Artist"), Instant.parse("2027-01-01T00:00:00Z"), 4, List.of());
        event.setId(EVENTO_ID);
        return event;
    }
}
