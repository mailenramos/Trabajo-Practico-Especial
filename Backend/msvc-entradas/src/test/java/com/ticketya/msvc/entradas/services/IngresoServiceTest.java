package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.clientes.AutorizacionAccesoCliente;
import com.ticketya.msvc.entradas.dto.IngresoResponseDTO;
import com.ticketya.msvc.entradas.entities.CopiaEvento;
import com.ticketya.msvc.entradas.entities.CopiaSector;
import com.ticketya.msvc.entradas.entities.Entrada;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.entities.Ingreso;
import com.ticketya.msvc.entradas.entities.SolicitudEmision;
import com.ticketya.msvc.entradas.exception.AccesoDenegadoException;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.exception.ResourceNotFoundException;
import com.ticketya.msvc.entradas.exception.ServicioNoDisponibleException;
import com.ticketya.msvc.entradas.repositories.ControlEventoRepository;
import com.ticketya.msvc.entradas.repositories.EntradaRepository;
import com.ticketya.msvc.entradas.repositories.IngresoRepository;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngresoServiceTest {

    private static final String ID_EVENTO = "evento-1";
    private static final Instant AHORA = Instant.parse("2026-12-15T20:30:00Z");
    private static final UsuarioActual PERSONAL = new UsuarioActual("personal-1", Set.of("PERSONAL_ACCESO"));
    private static final UsuarioActual ADMIN = new UsuarioActual("admin-1", Set.of("ADMIN"));
    private static final UsuarioActual CLIENTE = new UsuarioActual("cliente-1", Set.of("CLIENTE"));

    @Mock
    private EntradaRepository entradaRepository;
    @Mock
    private IngresoRepository ingresoRepository;
    @Mock
    private ControlEventoRepository controlEventoRepository;
    @Mock
    private AutorizacionAccesoCliente autorizacion;
    @Mock
    private TransactionTemplate transactionTemplate;

    private IngresoService service;

    @BeforeEach
    void preparar() {
        service = new IngresoService(entradaRepository, ingresoRepository, controlEventoRepository, autorizacion,
                transactionTemplate, Clock.fixed(AHORA, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any(TransactionCallback.class));
        when(ingresoRepository.saveAndFlush(any(Ingreso.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void personalAsignado_validaLaEntradaYRegistraElIngreso() {
        Entrada entrada = entrada(ID_EVENTO, EstadoEntrada.VIGENTE);
        when(autorizacion.estaAutorizado(ID_EVENTO, "personal-1")).thenReturn(true);
        when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));
        when(entradaRepository.cambiarEstado(eq(entrada.getId()), eq(EstadoEntrada.VIGENTE),
                eq(EstadoEntrada.UTILIZADA), any(LocalDateTime.class))).thenReturn(1);

        IngresoResponseDTO ingreso = service.validar(PERSONAL, ID_EVENTO, entrada.getCodigoQr());

        assertEquals(entrada.getId(), ingreso.idEntrada());
        assertEquals("personal-1", ingreso.validadoPor());
        assertEquals(AHORA, ingreso.validadoEl());
        assertFalse(ingreso.excepcional());
        assertEquals("Platea", ingreso.sector().nombre());
        assertEquals(3, ingreso.fila());
        verify(controlEventoRepository).bloquearCompartido(ID_EVENTO);
        verify(ingresoRepository).saveAndFlush(any(Ingreso.class));
    }

    @Test
    void administrador_validaSinConsultarAsignacionYQuedaComoExcepcional() {
        Entrada entrada = entrada(ID_EVENTO, EstadoEntrada.VIGENTE);
        when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));
        when(entradaRepository.cambiarEstado(any(), any(), any(), any())).thenReturn(1);

        IngresoResponseDTO ingreso = service.validar(ADMIN, ID_EVENTO, entrada.getCodigoQr());

        assertTrue(ingreso.excepcional());
        verifyNoInteractions(autorizacion);
    }

    @Test
    void usuarioSinRolDeAcceso_esRechazadoSinConsultarLaEntrada() {
        AccesoDenegadoException ex = assertThrows(AccesoDenegadoException.class,
                () -> service.validar(CLIENTE, ID_EVENTO, UUID.randomUUID()));

        assertEquals("ACCESO_DENEGADO", ex.getCodigo());
        verifyNoInteractions(autorizacion, entradaRepository);
    }

    @Test
    void personalSinAsignacionActiva_esRechazadoSinConsultarLaEntrada() {
        when(autorizacion.estaAutorizado(ID_EVENTO, "personal-1")).thenReturn(false);

        AccesoDenegadoException ex = assertThrows(AccesoDenegadoException.class,
                () -> service.validar(PERSONAL, ID_EVENTO, UUID.randomUUID()));

        assertEquals("PERSONAL_NO_AUTORIZADO", ex.getCodigo());
        verifyNoInteractions(entradaRepository);
    }

    @Test
    void siMs1NoResponde_laValidacionSeRechazaTemporalmente() {
        when(autorizacion.estaAutorizado(anyString(), anyString()))
                .thenThrow(new ServicioNoDisponibleException("AUTORIZACION_NO_DISPONIBLE", "caído", null));

        assertThrows(ServicioNoDisponibleException.class,
                () -> service.validar(PERSONAL, ID_EVENTO, UUID.randomUUID()));
        verifyNoInteractions(entradaRepository);
    }

    @Test
    void qrInexistente_responsdeNoEncontrado() {
        when(entradaRepository.buscarPorCodigoQr(any())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.validar(ADMIN, ID_EVENTO, UUID.randomUUID()));
    }

    @Test
    void entradaDeOtroEvento_seRechazaSinCambiarDeEstado() {
        Entrada entrada = entrada("otro-evento", EstadoEntrada.VIGENTE);
        when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> service.validar(ADMIN, ID_EVENTO, entrada.getCodigoQr()));

        assertEquals("ENTRADA_DE_OTRO_EVENTO", ex.getCodigo());
        verify(entradaRepository, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    void eventoCerrado_seRechaza() {
        Entrada entrada = entrada(ID_EVENTO, EstadoEntrada.VIGENTE);
        when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));
        when(controlEventoRepository.bloquearCompartido(ID_EVENTO)).thenReturn(true);

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> service.validar(ADMIN, ID_EVENTO, entrada.getCodigoQr()));

        assertEquals("EVENTO_CERRADO", ex.getCodigo());
        verify(entradaRepository, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    void entradaYaUtilizada_informaLaFechaDelIngresoAnterior() {
        Entrada entrada = entrada(ID_EVENTO, EstadoEntrada.UTILIZADA);
        Instant anterior = Instant.parse("2026-12-15T20:00:00Z");
        when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));
        when(entradaRepository.cambiarEstado(any(), any(), any(), any())).thenReturn(0);
        when(entradaRepository.buscarConDetalle(entrada.getId())).thenReturn(Optional.of(entrada));
        when(ingresoRepository.findByIdEntrada(entrada.getId()))
                .thenReturn(Optional.of(new Ingreso(entrada.getId(), ID_EVENTO, "otro", false, anterior)));

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> service.validar(ADMIN, ID_EVENTO, entrada.getCodigoQr()));

        assertEquals("ENTRADA_YA_UTILIZADA", ex.getCodigo());
        assertTrue(ex.getMessage().contains(anterior.toString()));
        verify(ingresoRepository, never()).saveAndFlush(any());
    }

    @Test
    void entradaEnReintegroODevuelta_noEstaHabilitada() {
        for (EstadoEntrada estado : new EstadoEntrada[] {EstadoEntrada.EN_REINTEGRO, EstadoEntrada.DEVUELTA}) {
            Entrada entrada = entrada(ID_EVENTO, estado);
            when(entradaRepository.buscarPorCodigoQr(entrada.getCodigoQr())).thenReturn(Optional.of(entrada));
            when(entradaRepository.cambiarEstado(any(), any(), any(), any())).thenReturn(0);
            when(entradaRepository.buscarConDetalle(entrada.getId())).thenReturn(Optional.of(entrada));

            BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                    () -> service.validar(ADMIN, ID_EVENTO, entrada.getCodigoQr()));

            assertEquals("ENTRADA_NO_HABILITADA", ex.getCodigo(), estado.name());
        }
        verify(ingresoRepository, never()).saveAndFlush(any());
    }

    private static Entrada entrada(String idEvento, EstadoEntrada estado) {
        UUID idCompra = UUID.randomUUID();
        Entrada entrada = new Entrada(new SolicitudEmision(idCompra), idCompra,
                new CopiaEvento(idEvento, "Recital", AHORA.plusSeconds(3600)),
                new CopiaSector("sector-1", "Platea"), 3, 12, "cliente-1");
        ReflectionTestUtils.setField(entrada, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entrada, "estado", estado);
        return entrada;
    }
}
