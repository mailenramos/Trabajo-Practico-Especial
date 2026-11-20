package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.dto.EmisionRequestDTO;
import com.ticketya.msvc.entradas.dto.EmisionResponseDTO;
import com.ticketya.msvc.entradas.dto.EntradaEmitidaDTO;
import com.ticketya.msvc.entradas.dto.EntradaResponseDTO;
import com.ticketya.msvc.entradas.dto.EventoResumenDTO;
import com.ticketya.msvc.entradas.dto.LugarAEmitirDTO;
import com.ticketya.msvc.entradas.dto.PaginaEntradasResponseDTO;
import com.ticketya.msvc.entradas.dto.ReintegroEventoResponseDTO;
import com.ticketya.msvc.entradas.dto.ResultadoEmisionDTO;
import com.ticketya.msvc.entradas.dto.SectorResumenDTO;
import com.ticketya.msvc.entradas.entities.CopiaEvento;
import com.ticketya.msvc.entradas.entities.CopiaSector;
import com.ticketya.msvc.entradas.entities.Entrada;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.entities.SolicitudEmision;
import com.ticketya.msvc.entradas.exception.AccesoDenegadoException;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.exception.ResourceNotFoundException;
import com.ticketya.msvc.entradas.exception.ValidacionException;
import com.ticketya.msvc.entradas.dto.EntradasCompraResponseDTO;
import com.ticketya.msvc.entradas.repositories.ControlEventoRepository;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import com.ticketya.msvc.entradas.repositories.CopiaEventoRepository;
import com.ticketya.msvc.entradas.repositories.CopiaSectorRepository;
import com.ticketya.msvc.entradas.repositories.EntradaRepository;
import com.ticketya.msvc.entradas.repositories.SolicitudEmisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EntradaServiceImpl implements EntradaService {

    private final EntradaRepository entradaRepository;
    private final SolicitudEmisionRepository solicitudEmisionRepository;
    private final CopiaEventoRepository copiaEventoRepository;
    private final CopiaSectorRepository copiaSectorRepository;
    private final ControlEventoRepository controlEventoRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // =====================================================================
    // Consultas del cliente
    // =====================================================================

    @Override
    @Transactional(readOnly = true)
    public PaginaEntradasResponseDTO getEntradas(UsuarioActual usuario,
                                                 EstadoEntrada estado,
                                                 String idEvento,
                                                 String idCliente,
                                                 int page,
                                                 int size) {
        Specification<Entrada> spec;
        if (usuario.esAdmin()) {
            // El administrador ve todas; puede filtrar por cliente.
            spec = (root, query, cb) -> null;
            if (idCliente != null && !idCliente.isBlank()) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("idCliente"), idCliente));
            }
        } else if (usuario.tieneRol(UsuarioActual.CLIENTE)) {
            // El cliente solo ve las propias: cualquier idCliente enviado se ignora.
            spec = (root, query, cb) -> cb.equal(root.get("idCliente"), usuario.id());
        } else {
            throw new AccesoDenegadoException("Solo un cliente o un administrador puede consultar entradas");
        }
        if (estado != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("estado"), estado));
        }
        if (idEvento != null && !idEvento.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("evento").get("idEvento"), idEvento));
        }

        Page<Entrada> resultado = entradaRepository.findAll(
                spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "creadoEl")));

        return new PaginaEntradasResponseDTO(
                resultado.getContent().stream().map(this::toDto).toList(),
                resultado.getNumber(),
                resultado.getSize(),
                resultado.getTotalElements(),
                resultado.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public EntradaResponseDTO getEntrada(UsuarioActual usuario, UUID id) {
        if (!usuario.esAdmin() && !usuario.tieneRol(UsuarioActual.CLIENTE)) {
            throw new AccesoDenegadoException("Solo un cliente o un administrador puede consultar entradas");
        }
        Entrada entrada = obtenerEntrada(id);
        if (!usuario.esAdmin() && !entrada.getIdCliente().equals(usuario.id())) {
            throw new AccesoDenegadoException("La entrada no pertenece al usuario");
        }
        return toDto(entrada);
    }

    // =====================================================================
    // Emisión (idempotente por idCompra)
    // =====================================================================

    /**
     * No es @Transactional a propósito: si dos emisiones de la misma compra llegan a la vez,
     * la base rechaza a una por la restricción única de id_compra. Esa transacción queda
     * abortada, así que el reintento se hace en una transacción nueva.
     */
    @Override
    public ResultadoEmisionDTO emitirEntradas(EmisionRequestDTO request) {
        validarLugares(request);
        try {
            return transactionTemplate.execute(status -> emitirEnTransaccion(request));
        } catch (DataIntegrityViolationException ex) {
            // Chocó con otra operación concurrente: o es la misma compra, o es un lugar ya vendido.
            ResultadoEmisionDTO existente =
                    transactionTemplate.execute(status -> buscarEmisionExistente(request));
            if (existente != null) {
                return existente;
            }
            throw new BusinessConflictException("ENTRADA_VIGENTE_DUPLICADA",
                    "Ya existe una entrada vigente para alguno de los lugares indicados");
        }
    }

    private ResultadoEmisionDTO emitirEnTransaccion(EmisionRequestDTO request) {
        // Una compra ya emitida devuelve sus entradas aunque el evento se haya cerrado después.
        ResultadoEmisionDTO existente = buscarEmisionExistente(request);
        if (existente != null) {
            return existente;
        }

        // Bloqueo compartido del evento: compite con el cierre (bloqueo exclusivo).
        controlEventoRepository.asegurar(request.evento().id());
        if (controlEventoRepository.bloquearCompartido(request.evento().id())) {
            throw new BusinessConflictException("EVENTO_CERRADO",
                    "El evento " + request.evento().id() + " está cerrado: no se emiten nuevas entradas");
        }

        // Copia mínima del evento y de los sectores (si ya existen, se conservan)
        copiaEventoRepository.insertarSiNoExiste(
                request.evento().id(), request.evento().nombre(), request.evento().comienzaEn());
        CopiaEvento evento = copiaEventoRepository.findById(request.evento().id()).orElseThrow();

        Map<String, CopiaSector> sectores = new LinkedHashMap<>();
        for (LugarAEmitirDTO lugar : request.lugares()) {
            sectores.computeIfAbsent(lugar.idSector(), id -> {
                copiaSectorRepository.insertarSiNoExiste(id, lugar.nombreSector());
                return copiaSectorRepository.findById(id).orElseThrow();
            });
        }

        // La restricción única de id_compra es la que cierra la puerta a emisiones duplicadas
        SolicitudEmision solicitud =
                solicitudEmisionRepository.saveAndFlush(new SolicitudEmision(request.idCompra()));

        List<Entrada> nuevas = request.lugares().stream()
                .map(l -> new Entrada(solicitud, request.idCompra(), evento,
                        sectores.get(l.idSector()), l.fila(), l.numero(), request.idCliente()))
                .toList();

        // El índice único parcial (evento, sector, fila, número) WHERE estado = 'VIGENTE' impide la doble venta
        entradaRepository.saveAllAndFlush(nuevas);

        return new ResultadoEmisionDTO(toEmisionResponse(request.idCompra(), nuevas), true);
    }

    /** Si la compra ya fue emitida: devuelve lo emitido (mismos datos) o falla (datos distintos). */
    private ResultadoEmisionDTO buscarEmisionExistente(EmisionRequestDTO request) {
        if (solicitudEmisionRepository.findByIdCompra(request.idCompra()).isEmpty()) {
            return null;
        }
        List<Entrada> existentes = entradaRepository.buscarPorCompra(request.idCompra());
        if (!mismosDatos(request, existentes)) {
            throw new BusinessConflictException("IDEMPOTENCIA_CONFLICTO",
                    "La compra ya fue procesada con datos diferentes");
        }
        return new ResultadoEmisionDTO(toEmisionResponse(request.idCompra(), existentes), false);
    }

    private boolean mismosDatos(EmisionRequestDTO request, List<Entrada> existentes) {
        if (existentes.isEmpty()) {
            return false;
        }
        boolean mismoClienteYEvento = existentes.stream().allMatch(e ->
                e.getIdCliente().equals(request.idCliente())
                        && e.getEvento().getIdEvento().equals(request.evento().id()));
        if (!mismoClienteYEvento) {
            return false;
        }
        List<String> pedidos = request.lugares().stream()
                .map(l -> l.idSector() + "|" + l.fila() + "|" + l.numero())
                .sorted()
                .toList();
        List<String> emitidos = existentes.stream()
                .map(e -> e.getSector().getIdSector() + "|" + e.getFila() + "|" + e.getNumero())
                .sorted()
                .toList();
        return pedidos.equals(emitidos);
    }

    /**
     * Valida que los lugares tengan una ubicación coherente
     * y que no se repitan dentro del mismo pedido.
     */
    private void validarLugares(EmisionRequestDTO request) {
        Set<String> vistos = new HashSet<>();

        for (LugarAEmitirDTO lugar : request.lugares()) {

            // Sector no numerado: fila y numero deben ser nulos.
            if (lugar.numero() == null) {
                if (lugar.fila() != null) {
                    throw new ValidacionException(
                            "En un sector no numerado, fila y número deben ser nulos");
                }
                continue;
            }

            // Sector numerado: necesita fila.
            if (lugar.fila() == null) {
                throw new ValidacionException(
                        "El lugar numerado " + lugar.numero()
                                + " del sector " + lugar.idSector()
                                + " necesita una fila");
            }

            String clave = lugar.idSector() + "|" + lugar.fila() + "|" + lugar.numero();

            if (!vistos.add(clave)) {
                throw new ValidacionException(
                        "El lugar " + lugar.fila() + "-" + lugar.numero()
                                + " del sector " + lugar.idSector()
                                + " está repetido en la solicitud");
            }
        }
    }

    // =====================================================================
    // Consulta interna y transiciones de estado
    // =====================================================================

    @Override
    @Transactional(readOnly = true)
    public EntradaResponseDTO consultarEntradaInterna(UUID id) {
        return toDto(obtenerEntrada(id));
    }

    @Override
    @Transactional(readOnly = true)
    public EntradasCompraResponseDTO consultarEntradasDeCompra(UUID idCompra) {
        return new EntradasCompraResponseDTO(idCompra,
                entradaRepository.buscarPorCompra(idCompra).stream().map(this::toDto).toList());
    }

    /**
     * VIGENTE -> EN_REINTEGRO (bloqueo por devolución). Compite con la validación de QR: la
     * actualización condicional deja ganar a una sola. Repetirla no cambia nada. Sobre una
     * UTILIZADA responde ENTRADA_UTILIZADA; sobre una DEVUELTA, TRANSICION_INVALIDA.
     */
    @Override
    @Transactional
    public EntradaResponseDTO marcarEnReintegro(UUID id) {
        Entrada entrada = obtenerEntrada(id);
        if (entrada.getEstado() == EstadoEntrada.VIGENTE) {
            entradaRepository.cambiarEstado(id, EstadoEntrada.VIGENTE, EstadoEntrada.EN_REINTEGRO, ahora());
            entrada = obtenerEntrada(id);   // por si otra solicitud se adelantó
        }
        if (entrada.getEstado() == EstadoEntrada.EN_REINTEGRO) {
            return toDto(entrada);
        }
        if (entrada.getEstado() == EstadoEntrada.UTILIZADA) {
            throw new BusinessConflictException("ENTRADA_UTILIZADA",
                    "La entrada ya se utilizó para ingresar al evento y no puede devolverse");
        }
        throw transicionInvalida(entrada, EstadoEntrada.EN_REINTEGRO);
    }

    /** EN_REINTEGRO -> DEVUELTA. Repetirla no cambia nada. Sobre una VIGENTE es un conflicto. */
    @Override
    @Transactional
    public EntradaResponseDTO marcarDevuelta(UUID id) {
        Entrada entrada = obtenerEntrada(id);
        if (entrada.getEstado() == EstadoEntrada.EN_REINTEGRO) {
            entradaRepository.cambiarEstado(id, EstadoEntrada.EN_REINTEGRO, EstadoEntrada.DEVUELTA, ahora());
            entrada = obtenerEntrada(id);
        }
        if (entrada.getEstado() == EstadoEntrada.DEVUELTA) {
            return toDto(entrada);
        }
        throw transicionInvalida(entrada, EstadoEntrada.DEVUELTA);
    }

    /**
     * Evento cancelado: registra el cierre de forma persistente (aunque no haya entradas) y pasa
     * las entradas VIGENTE a EN_REINTEGRO en la misma transacción. Las UTILIZADA conservan su
     * estado. El bloqueo exclusivo espera a las emisiones y validaciones en curso; las posteriores
     * ven el evento cerrado. Repetirlo responde cerrado=true y 0 marcadas.
     */
    @Override
    @Transactional
    public ReintegroEventoResponseDTO reintegroPorCancelacionEvento(String idEvento) {
        controlEventoRepository.asegurar(idEvento);
        controlEventoRepository.bloquearExclusivo(idEvento);
        controlEventoRepository.cerrar(idEvento, Instant.now(clock));
        int marcadas = entradaRepository.cambiarEstadoPorEvento(
                idEvento, EstadoEntrada.VIGENTE, EstadoEntrada.EN_REINTEGRO, ahora());
        return new ReintegroEventoResponseDTO(idEvento, marcadas, true);
    }

    // =====================================================================
    // Auxiliares
    // =====================================================================

    private Entrada obtenerEntrada(UUID id) {
        return entradaRepository.buscarConDetalle(id)
                .orElseThrow(() -> new ResourceNotFoundException("No existe la entrada indicada"));
    }

    private BusinessConflictException transicionInvalida(Entrada entrada, EstadoEntrada destino) {
        return new BusinessConflictException("TRANSICION_INVALIDA",
                "La entrada no puede pasar a " + destino + " desde " + entrada.getEstado());
    }

    private static LocalDateTime ahora() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private EntradaResponseDTO toDto(Entrada e) {
        CopiaEvento ev = e.getEvento();
        CopiaSector sec = e.getSector();
        return new EntradaResponseDTO(
                e.getId(),
                e.getCodigoQr(),
                e.getEstado(),
                new EventoResumenDTO(ev.getIdEvento(), ev.getNombre(), ev.getComienzaEn()),
                new SectorResumenDTO(sec.getIdSector(), sec.getNombre()),
                e.getFila(),
                e.getNumero(),
                e.getIdCompra(),
                e.getIdCliente());
    }

    private EmisionResponseDTO toEmisionResponse(UUID idCompra, List<Entrada> entradas) {
        return new EmisionResponseDTO(idCompra, entradas.stream()
                .map(e -> new EntradaEmitidaDTO(e.getId(), e.getSector().getIdSector(),
                        e.getFila(), e.getNumero(), e.getCodigoQr(), e.getEstado()))
                .toList());
    }
}
