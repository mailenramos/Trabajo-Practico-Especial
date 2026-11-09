package ar.edu.unicen.ms_disponibilidad_reservas.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.DetalleDevolucionRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.LiberarReservaRequest;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.AsientoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.CantidadClienteEventoRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.DevolucionRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.EventoDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.ReservaRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.SectorDisponibilidadRepository;
import ar.edu.unicen.ms_disponibilidad_reservas.repository.DevolucionRepository.RegistroDevolucion;
import ar.edu.unicen.ms_disponibilidad_reservas.model.CantidadClienteEvento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.DetalleReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.EventoDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.Reserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.SectorDisponibilidad;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;

class DevolucionServiceTests {

    private static final String REFUND_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void duplicateDetailsAreRejectedBeforeOpeningATransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DevolucionService service = service(transactionManager);
        DetalleDevolucionRequest asiento = new DetalleDevolucionRequest("SEC-01", 1, 5, null);
        LiberarReservaRequest request = new LiberarReservaRequest("DEVOLUCION_APROBADA",
                "550e8400-e29b-41d4-a716-446655440000", List.of(asiento, asiento));

        assertThrows(IllegalArgumentException.class, () -> service.devolverAprobada("00000000-0000-0000-0000-000000000001", request));
        verifyNoInteractions(transactionManager);
    }

    @Test
    void invalidUuidIsRejectedBeforeOpeningATransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DevolucionService service = service(transactionManager);
        LiberarReservaRequest request = new LiberarReservaRequest("DEVOLUCION_APROBADA", "not-a-uuid",
                List.of(new DetalleDevolucionRequest("SEC-01", 1, 5, null)));

        assertThrows(IllegalArgumentException.class, () -> service.devolverAprobada("00000000-0000-0000-0000-000000000001", request));
        verifyNoInteractions(transactionManager);
    }

    @Test
    void malformedOrEmptyRefundDetailsAreRejectedBeforeOpeningATransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DevolucionService service = service(transactionManager);
        String id = "550e8400-e29b-41d4-a716-446655440000";
        List<LiberarReservaRequest> invalidRequests = List.of(
                new LiberarReservaRequest("DEVOLUCION_APROBADA", id, List.of()),
                new LiberarReservaRequest("DEVOLUCION_APROBADA", id,
                        java.util.Arrays.asList((DetalleDevolucionRequest) null)),
                new LiberarReservaRequest("DEVOLUCION_APROBADA", id,
                        List.of(new DetalleDevolucionRequest("SEC-01", 1, null, 1))),
                new LiberarReservaRequest("DEVOLUCION_APROBADA", id, List.of(
                        new DetalleDevolucionRequest("SEC-GEN", null, null, 1),
                        new DetalleDevolucionRequest("SEC-GEN", null, null, 2))));

        for (LiberarReservaRequest request : invalidRequests) {
            assertThrows(IllegalArgumentException.class, () -> service.devolverAprobada("00000000-0000-0000-0000-000000000001", request));
        }
        verifyNoInteractions(transactionManager);
    }

    @Test
    void successfulNumberedRefundAndIdenticalRetryApplyInventoryAndCountsOnlyOnce() {
        Fixture fixture = new Fixture(TipoSector.NUMERADO, 1, 3, 0);
        DetalleDevolucionRequest seat = new DetalleDevolucionRequest("SEC-01", 1, 5, null);
        LiberarReservaRequest request = refund(REFUND_ID, seat);

        fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", request);
        fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", request);

        assertNotNull(fixture.refund.get());
        assertEquals("00000000-0000-0000-0000-000000000001", fixture.refund.get().reservaId());
        assertEquals(2, fixture.aggregate.getCantidadConfirmada());
        assertEquals(0, fixture.aggregate.getCantidadReservada());
        verify(fixture.devolutions, times(1)).insert(eq(REFUND_ID), eq("00000000-0000-0000-0000-000000000001"), anyString());
        verify(fixture.availability, times(1)).devolver(any());
        verify(fixture.quantities, times(1)).saveAndFlush(fixture.aggregate);
        verify(fixture.devolutions, times(1)).insertReturnedSeat(REFUND_ID, "00000000-0000-0000-0000-000000000001", 21L);
    }

    @Test
    void reusingRefundIdForAnotherReservationOrDifferentContentConflictsWithoutEffects() {
        Fixture fixture = new Fixture(TipoSector.NUMERADO, 1, 3, 0);
        LiberarReservaRequest original = refund(REFUND_ID,
                new DetalleDevolucionRequest("SEC-01", 1, 5, null));
        fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", original);
        int confirmedAfterFirst = fixture.aggregate.getCantidadConfirmada();

        assertThrows(ReservaConflictException.class,
                () -> fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000002", original));
        assertThrows(ReservaConflictException.class,
                () -> fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", refund(REFUND_ID,
                        new DetalleDevolucionRequest("SEC-01", 1, 6, null))));

        assertEquals(confirmedAfterFirst, fixture.aggregate.getCantidadConfirmada());
        verify(fixture.availability, times(1)).devolver(any());
        verify(fixture.quantities, times(1)).saveAndFlush(fixture.aggregate);
        verify(fixture.devolutions, times(1)).insert(anyString(), anyString(), anyString());
    }

    @Test
    void differentRefundIdCannotReturnNumberedSeatAgain() {
        Fixture fixture = new Fixture(TipoSector.NUMERADO, 1, 3, 0);
        LiberarReservaRequest first = refund(REFUND_ID,
                new DetalleDevolucionRequest("SEC-01", 1, 5, null));
        fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", first);
        int confirmedAfterFirst = fixture.aggregate.getCantidadConfirmada();

        assertThrows(ReservaConflictException.class,
                () -> fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", refund(
                        "550e8400-e29b-41d4-a716-446655440001",
                        new DetalleDevolucionRequest("SEC-01", 1, 5, null))));

        assertEquals(confirmedAfterFirst, fixture.aggregate.getCantidadConfirmada());
        verify(fixture.availability, times(1)).devolver(any());
        verify(fixture.quantities, times(1)).saveAndFlush(fixture.aggregate);
        verify(fixture.devolutions, times(1)).insertReturnedSeat(anyString(), anyString(), anyLong());
    }

    @Test
    void differentRefundIdCannotExceedAlreadyReturnedNonNumberedQuantity() {
        Fixture fixture = new Fixture(TipoSector.NO_NUMERADO, 3, 5, 0);
        fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001",
                refund(REFUND_ID, new DetalleDevolucionRequest("SEC-01", null, null, 2)));
        int confirmedAfterFirst = fixture.aggregate.getCantidadConfirmada();

        assertThrows(ReservaConflictException.class,
                () -> fixture.service.devolverAprobada("00000000-0000-0000-0000-000000000001", refund(
                        "550e8400-e29b-41d4-a716-446655440002",
                        new DetalleDevolucionRequest("SEC-01", null, null, 2))));

        assertEquals(confirmedAfterFirst, fixture.aggregate.getCantidadConfirmada());
        verify(fixture.availability, times(1)).devolver(any());
        verify(fixture.quantities, times(1)).saveAndFlush(fixture.aggregate);
    }

    private static LiberarReservaRequest refund(String id, DetalleDevolucionRequest detail) {
        return new LiberarReservaRequest("DEVOLUCION_APROBADA", id, List.of(detail));
    }

    private static class Fixture {
        final ReservaRepository reservations = mock(ReservaRepository.class);
        final CantidadClienteEventoRepository quantities = mock(CantidadClienteEventoRepository.class);
        final SectorDisponibilidadRepository sectors = mock(SectorDisponibilidadRepository.class);
        final AsientoDisponibilidadRepository seats = mock(AsientoDisponibilidadRepository.class);
        final DevolucionRepository devolutions = mock(DevolucionRepository.class);
        final DisponibilidadService availability = mock(DisponibilidadService.class);
        final EventoDisponibilidadRepository events = mock(EventoDisponibilidadRepository.class);
        final DisponibilidadAgotamientoService exhaustion = mock(DisponibilidadAgotamientoService.class);
        final CantidadClienteEvento aggregate = new CantidadClienteEvento("cliente-1", "evento-1");
        final Reserva reservation = new Reserva();
        final DetalleReserva original = new DetalleReserva();
        final SectorDisponibilidad sector = new SectorDisponibilidad();
        final AtomicReference<RegistroDevolucion> refund = new AtomicReference<>();
        final Set<Long> returnedSeats = new HashSet<>();
        final java.util.concurrent.atomic.AtomicInteger returnedQuantity = new java.util.concurrent.atomic.AtomicInteger();
        final DevolucionService service;

        Fixture(TipoSector type, int originalQuantity, int sold, int alreadyReturned) {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                    .thenAnswer(ignored -> new SimpleTransactionStatus());
            org.mockito.Mockito.doNothing().when(transactionManager).commit(any());
            org.mockito.Mockito.doNothing().when(transactionManager).rollback(any());

            ReflectionTestUtils.setField(reservation, "id", "00000000-0000-0000-0000-000000000001");
            reservation.setEventoId("evento-1");
            reservation.setUsuarioId("cliente-1");
            reservation.setEstado(EstadoReserva.CONFIRMADA);
            ReflectionTestUtils.setField(original, "id", 21L);
            original.setSectorId("SEC-01");
            original.setFila(type == TipoSector.NUMERADO ? 1 : null);
            original.setNumero(type == TipoSector.NUMERADO ? 5 : null);
            original.setCantidad(originalQuantity);
            reservation.agregarDetalle(original);
            aggregate.setCantidadConfirmada(3);
            sector.setEventoId("evento-1");
            sector.setSectorId("SEC-01");
            sector.setTipo(type);
            sector.setCapacidadTotal(10);
            sector.setCantidadVendida(sold);

            when(devolutions.findById(anyString()))
                    .thenAnswer(invocation -> {
                        RegistroDevolucion existing = refund.get();
                        return existing != null && existing.devolucionId().equals(invocation.getArgument(0))
                                ? Optional.of(existing) : Optional.empty();
                    });
            when(devolutions.findByIdForUpdate(anyString())).thenReturn(Optional.empty());
            doAnswer(invocation -> {
                refund.set(new RegistroDevolucion(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
                return null;
            }).when(devolutions).insert(anyString(), anyString(), anyString());
            doAnswer(invocation -> {
                returnedSeats.add(invocation.getArgument(2));
                return null;
            }).when(devolutions).insertReturnedSeat(anyString(), anyString(), anyLong());
            doAnswer(invocation -> {
                returnedQuantity.addAndGet(invocation.getArgument(3));
                return null;
            }).when(devolutions).insertReturnedQuantity(anyString(), anyString(), anyLong(), any(Integer.class));
            when(devolutions.seatWasReturned(anyLong()))
                    .thenAnswer(invocation -> returnedSeats.contains(invocation.getArgument(0)));
            returnedQuantity.set(alreadyReturned);
            when(devolutions.returnedQuantity(21L)).thenAnswer(ignored -> returnedQuantity.get());
            when(reservations.findEventoIdById("00000000-0000-0000-0000-000000000001")).thenReturn(Optional.of("evento-1"));
            when(reservations.findByIdForUpdate("00000000-0000-0000-0000-000000000001")).thenReturn(Optional.of(reservation));
            when(events.findByEventoIdForUpdate("evento-1"))
                    .thenReturn(Optional.of(new EventoDisponibilidad()));
            when(sectors.findByEventoIdAndSectorId("evento-1", "SEC-01"))
                    .thenReturn(Optional.of(sector));
            when(seats.existsByEventoIdAndSectorIdAndFilaAndNumeroAndEstado(
                    "evento-1", "SEC-01", 1, 5, EstadoAsiento.VENDIDO))
                    .thenReturn(type == TipoSector.NUMERADO);
            when(quantities.findByUsuarioIdAndEventoIdForUpdate("cliente-1", "evento-1"))
                    .thenReturn(Optional.of(aggregate));
            when(exhaustion.estaAgotado("evento-1")).thenReturn(false);

            service = new DevolucionService(reservations, quantities, sectors, seats, devolutions,
                    availability, events, exhaustion, transactionManager);
            ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        }

    }

    private DevolucionService service(PlatformTransactionManager transactionManager) {
        return new DevolucionService(
                mock(ReservaRepository.class),
                mock(CantidadClienteEventoRepository.class),
                mock(SectorDisponibilidadRepository.class),
                mock(AsientoDisponibilidadRepository.class),
                mock(DevolucionRepository.class),
                mock(DisponibilidadService.class),
                mock(EventoDisponibilidadRepository.class),
                mock(DisponibilidadAgotamientoService.class),
                transactionManager);
    }
}
