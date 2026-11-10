package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.EventoResumenDTO;
import com.ticketya.msvc.entradas.dto.LugarAEmitirDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.entities.CopiaEvento;
import com.ticketya.msvc.entradas.entities.CopiaSector;
import com.ticketya.msvc.entradas.entities.Entrada;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.entities.SolicitudEmision;
import com.ticketya.msvc.entradas.exception.AccesoDenegadoException;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.exception.ResourceNotFoundException;
import com.ticketya.msvc.entradas.exception.ValidacionException;
import com.ticketya.msvc.entradas.repositories.CopiaEventoRepository;
import com.ticketya.msvc.entradas.repositories.CopiaSectorRepository;
import com.ticketya.msvc.entradas.repositories.EntradaRepository;
import com.ticketya.msvc.entradas.repositories.SolicitudEmisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntradaServiceImplTest {

    private static final String ID_EVENTO = "evento-1";
    private static final String ID_SECTOR = "sector-1";
    private static final String ID_CLIENTE = "cliente-1";
    private static final LocalDateTime COMIENZA_EN = LocalDateTime.of(2026, 12, 15, 21, 0);

    @Mock
    private EntradaRepository entradaRepository;
    @Mock
    private SolicitudEmisionRepository solicitudEmisionRepository;
    @Mock
    private CopiaEventoRepository copiaEventoRepository;
    @Mock
    private CopiaSectorRepository copiaSectorRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Captor
    private ArgumentCaptor<List<Entrada>> entradasCaptor;

    @InjectMocks
    private EntradaServiceImpl service;

    @BeforeEach
    void ejecutarCallbacksTransaccionales() {
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any(TransactionCallback.class));
    }

    @Test
    void emitirEntradas_creaSolicitudYCreaEntradasVigentes() {
        EmisionRequestDTO request = request(UUID.randomUUID(), List.of(lugar(3, 12), lugar(3, 13)));
        CopiaEvento evento = new CopiaEvento(ID_EVENTO, "Recital", COMIENZA_EN);
        CopiaSector sector = new CopiaSector(ID_SECTOR, "Platea");

        when(solicitudEmisionRepository.findByIdCompra(request.idCompra())).thenReturn(Optional.empty());
        when(copiaEventoRepository.findById(ID_EVENTO)).thenReturn(Optional.of(evento));
        when(copiaSectorRepository.findById(ID_SECTOR)).thenReturn(Optional.of(sector));
        when(solicitudEmisionRepository.saveAndFlush(any(SolicitudEmision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entradaRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResultadoEmisionDTO result = service.emitirEntradas(request);

        assertTrue(result.creada());
        assertEquals(request.idCompra(), result.emision().idCompra());
        assertEquals(2, result.emision().entradas().size());
        assertTrue(result.emision().entradas().stream()
                .allMatch(entrada -> entrada.estado() == EstadoEntrada.VIGENTE));
        assertTrue(result.emision().entradas().stream()
                .allMatch(entrada -> entrada.codigoQr() != null));
        verify(copiaEventoRepository).insertarSiNoExiste(ID_EVENTO, "Recital", COMIENZA_EN);
        verify(copiaSectorRepository).insertarSiNoExiste(ID_SECTOR, "Platea");
        verify(solicitudEmisionRepository).saveAndFlush(any(SolicitudEmision.class));
        verify(entradaRepository).saveAllAndFlush(entradasCaptor.capture());
        assertEquals(2, entradasCaptor.getValue().size());
        assertTrue(entradasCaptor.getValue().stream()
                .allMatch(entrada -> entrada.getEstado() == EstadoEntrada.VIGENTE));
    }

    @Test
    void emitirEntradas_conMismaCompraYMismosDatos_devuelveEmisionExistente() {
        UUID idCompra = UUID.randomUUID();
        EmisionRequestDTO request = request(idCompra, List.of(lugar(3, 12)));
        Entrada existente = entrada(ID_CLIENTE, idCompra, EstadoEntrada.VIGENTE, 3, 12);

        when(solicitudEmisionRepository.findByIdCompra(idCompra))
                .thenReturn(Optional.of(new SolicitudEmision(idCompra)));
        when(entradaRepository.buscarPorCompra(idCompra)).thenReturn(List.of(existente));

        ResultadoEmisionDTO result = service.emitirEntradas(request);

        assertFalse(result.creada());
        assertEquals(idCompra, result.emision().idCompra());
        assertEquals(1, result.emision().entradas().size());
        verify(solicitudEmisionRepository, never()).saveAndFlush(any());
        verify(entradaRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void emitirEntradas_conMismaCompraYDatosDistintos_lanzaConflictoDeIdempotencia() {
        UUID idCompra = UUID.randomUUID();
        EmisionRequestDTO request = request(idCompra, List.of(lugar(3, 12)));
        Entrada existente = entrada(ID_CLIENTE, idCompra, EstadoEntrada.VIGENTE, 3, 99);

        when(solicitudEmisionRepository.findByIdCompra(idCompra))
                .thenReturn(Optional.of(new SolicitudEmision(idCompra)));
        when(entradaRepository.buscarPorCompra(idCompra)).thenReturn(List.of(existente));

        BusinessConflictException exception = assertThrows(BusinessConflictException.class,
                () -> service.emitirEntradas(request));

        assertEquals("IDEMPOTENCIA_CONFLICTO", exception.getCodigo());
        verify(solicitudEmisionRepository, never()).saveAndFlush(any());
    }

    @Test
    void emitirEntradas_rechazaSectorNoNumeradoConFila() {
        EmisionRequestDTO request = request(UUID.randomUUID(),
                List.of(new LugarAEmitirDTO(ID_SECTOR, "Platea", 3, null)));

        assertThrows(ValidacionException.class, () -> service.emitirEntradas(request));

        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void emitirEntradas_rechazaLugarNumeradoSinFila() {
        EmisionRequestDTO request = request(UUID.randomUUID(),
                List.of(new LugarAEmitirDTO(ID_SECTOR, "Platea", null, 12)));

        assertThrows(ValidacionException.class, () -> service.emitirEntradas(request));

        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void emitirEntradas_rechazaLugarNumeradoRepetidoEnLaSolicitud() {
        EmisionRequestDTO request = request(UUID.randomUUID(), List.of(lugar(3, 12), lugar(3, 12)));

        assertThrows(ValidacionException.class, () -> service.emitirEntradas(request));

        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void marcarEnReintegro_desdeVigente_actualizaYDevuelveElNuevoEstado() {
        UUID id = UUID.randomUUID();
        Entrada vigente = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.VIGENTE, 3, 12);
        Entrada enReintegro = entrada(ID_CLIENTE, vigente.getIdCompra(), EstadoEntrada.EN_REINTEGRO, 3, 12);

        when(entradaRepository.buscarConDetalle(id))
                .thenReturn(Optional.of(vigente), Optional.of(enReintegro));

        EntradaResponseDTO result = service.marcarEnReintegro(id);

        assertEquals(EstadoEntrada.EN_REINTEGRO, result.estado());
        verify(entradaRepository).cambiarEstado(eq(id), eq(EstadoEntrada.VIGENTE),
                eq(EstadoEntrada.EN_REINTEGRO), any(LocalDateTime.class));
    }

    @Test
    void marcarEnReintegro_sobreEntradaYaEnReintegro_esIdempotente() {
        UUID id = UUID.randomUUID();
        Entrada enReintegro = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.EN_REINTEGRO, 3, 12);
        when(entradaRepository.buscarConDetalle(id)).thenReturn(Optional.of(enReintegro));

        EntradaResponseDTO result = service.marcarEnReintegro(id);

        assertEquals(EstadoEntrada.EN_REINTEGRO, result.estado());
        verify(entradaRepository, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    void marcarDevuelta_desdeEnReintegro_actualizaYDevuelveElNuevoEstado() {
        UUID id = UUID.randomUUID();
        Entrada enReintegro = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.EN_REINTEGRO, 3, 12);
        Entrada devuelta = entrada(ID_CLIENTE, enReintegro.getIdCompra(), EstadoEntrada.DEVUELTA, 3, 12);
        when(entradaRepository.buscarConDetalle(id))
                .thenReturn(Optional.of(enReintegro), Optional.of(devuelta));

        EntradaResponseDTO result = service.marcarDevuelta(id);

        assertEquals(EstadoEntrada.DEVUELTA, result.estado());
        verify(entradaRepository).cambiarEstado(eq(id), eq(EstadoEntrada.EN_REINTEGRO),
                eq(EstadoEntrada.DEVUELTA), any(LocalDateTime.class));
    }

    @Test
    void marcarDevuelta_sobreEntradaYaDevuelta_esIdempotente() {
        UUID id = UUID.randomUUID();
        Entrada devuelta = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.DEVUELTA, 3, 12);
        when(entradaRepository.buscarConDetalle(id)).thenReturn(Optional.of(devuelta));

        EntradaResponseDTO result = service.marcarDevuelta(id);

        assertEquals(EstadoEntrada.DEVUELTA, result.estado());
        verify(entradaRepository, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    void marcarDevuelta_desdeVigente_lanzaConflictoDeTransicion() {
        UUID id = UUID.randomUUID();
        when(entradaRepository.buscarConDetalle(id)).thenReturn(Optional.of(
                entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.VIGENTE, 3, 12)));

        BusinessConflictException exception = assertThrows(BusinessConflictException.class,
                () -> service.marcarDevuelta(id));

        assertEquals("TRANSICION_INVALIDA", exception.getCodigo());
        verify(entradaRepository, never()).cambiarEstado(any(), any(), any(), any());
    }

    @Test
    void reintegroPorCancelacionEvento_devuelveCantidadActualizadaPorRepositorio() {
        when(entradaRepository.cambiarEstadoPorEvento(eq(ID_EVENTO), eq(EstadoEntrada.VIGENTE),
                eq(EstadoEntrada.EN_REINTEGRO), any(LocalDateTime.class))).thenReturn(4);

        var result = service.reintegroPorCancelacionEvento(ID_EVENTO);

        assertEquals(ID_EVENTO, result.idEvento());
        assertEquals(4, result.entradasMarcadas());
    }

    @Test
    void getEntradas_devuelvePaginaMapeadaYOrdenadaPorFechaDescendente() {
        Entrada primera = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.VIGENTE, 3, 12);
        Entrada segunda = entrada(ID_CLIENTE, UUID.randomUUID(), EstadoEntrada.EN_REINTEGRO, 3, 13);
        when(entradaRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(primera, segunda),
                        org.springframework.data.domain.PageRequest.of(1, 2), 7));

        PaginaEntradasResponseDTO result = service.getEntradas(ID_CLIENTE, EstadoEntrada.VIGENTE,
                ID_EVENTO, 1, 2);

        assertEquals(2, result.content().size());
        assertEquals(1, result.page());
        assertEquals(2, result.size());
        assertEquals(7, result.totalElements());
        assertEquals(4, result.totalPages());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(entradaRepository).findAll(any(Specification.class), pageableCaptor.capture());
        assertTrue(pageableCaptor.getValue().getSort().getOrderFor("creadoEl").isDescending());
    }

    @Test
    void getEntrada_deOtroUsuario_lanzaAccesoDenegado() {
        UUID id = UUID.randomUUID();
        when(entradaRepository.buscarConDetalle(id)).thenReturn(Optional.of(
                entrada("otro-cliente", UUID.randomUUID(), EstadoEntrada.VIGENTE, 3, 12)));

        assertThrows(AccesoDenegadoException.class, () -> service.getEntrada(ID_CLIENTE, id));
    }

    @Test
    void consultarEntradaInterna_inexistente_lanzaRecursoNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(entradaRepository.buscarConDetalle(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.consultarEntradaInterna(id));
    }

    private EmisionRequestDTO request(UUID idCompra, List<LugarAEmitirDTO> lugares) {
        return new EmisionRequestDTO(idCompra, ID_CLIENTE,
                new EventoResumenDTO(ID_EVENTO, "Recital", COMIENZA_EN), lugares);
    }

    private LugarAEmitirDTO lugar(int fila, int numero) {
        return new LugarAEmitirDTO(ID_SECTOR, "Platea", fila, numero);
    }

    private Entrada entrada(String idCliente, UUID idCompra, EstadoEntrada estado, int fila, int numero) {
        CopiaEvento evento = new CopiaEvento(ID_EVENTO, "Recital", COMIENZA_EN);
        CopiaSector sector = new CopiaSector(ID_SECTOR, "Platea");
        Entrada entrada = new Entrada(new SolicitudEmision(idCompra), idCompra, evento, sector, fila, numero, idCliente);
        ReflectionTestUtils.setField(entrada, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entrada, "codigoQr", UUID.randomUUID());
        ReflectionTestUtils.setField(entrada, "estado", estado);
        return entrada;
    }
}
