package com.ticketya.msvc.entradas.services;

import com.ticketya.msvc.entradas.clientes.AutorizacionAccesoCliente;
import com.ticketya.msvc.entradas.dto.IngresoResponseDTO;
import com.ticketya.msvc.entradas.dto.SectorResumenDTO;
import com.ticketya.msvc.entradas.entities.Entrada;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import com.ticketya.msvc.entradas.entities.Ingreso;
import com.ticketya.msvc.entradas.exception.AccesoDenegadoException;
import com.ticketya.msvc.entradas.exception.BusinessConflictException;
import com.ticketya.msvc.entradas.exception.ResourceNotFoundException;
import com.ticketya.msvc.entradas.repositories.ControlEventoRepository;
import com.ticketya.msvc.entradas.repositories.EntradaRepository;
import com.ticketya.msvc.entradas.repositories.IngresoRepository;
import com.ticketya.msvc.entradas.seguridad.UsuarioActual;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Validación del QR en el acceso a un evento (US-5.1 a US-5.3).
 *
 * <ol>
 *   <li>Roles: PERSONAL_ACCESO o ADMIN.</li>
 *   <li>Sin ADMIN: consulta a MS1 la asignación activa, fuera de la transacción y en cada
 *       validación (sin caché), para que una revocación tenga efecto inmediato.</li>
 *   <li>Transacción: entrada del evento, bloqueo compartido del evento (compite con el cierre) y
 *       actualización condicional VIGENTE -> UTILIZADA, que deja ganar a una sola validación o
 *       al bloqueo por devolución.</li>
 * </ol>
 */
@Service
public class IngresoService {

    private static final Logger LOG = LoggerFactory.getLogger(IngresoService.class);

    private final EntradaRepository entradaRepository;
    private final IngresoRepository ingresoRepository;
    private final ControlEventoRepository controlEventoRepository;
    private final AutorizacionAccesoCliente autorizacion;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public IngresoService(EntradaRepository entradaRepository, IngresoRepository ingresoRepository,
                          ControlEventoRepository controlEventoRepository, AutorizacionAccesoCliente autorizacion,
                          TransactionTemplate transactionTemplate, Clock clock) {
        this.entradaRepository = entradaRepository;
        this.ingresoRepository = ingresoRepository;
        this.controlEventoRepository = controlEventoRepository;
        this.autorizacion = autorizacion;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public IngresoResponseDTO validar(UsuarioActual usuario, String idEvento, UUID codigoQr) {
        // 1 y 2. Autorización primero: un usuario no autorizado no averigua si el QR existe.
        boolean excepcional = usuario.esAdmin();
        if (!excepcional) {
            if (!usuario.tieneRol(UsuarioActual.PERSONAL_ACCESO)) {
                throw new AccesoDenegadoException(
                        "Solo el personal de acceso o un administrador puede validar entradas");
            }
            if (!autorizacion.estaAutorizado(idEvento, usuario.id())) {
                LOG.info("ingreso_rechazado evento={} usuario={} motivo=PERSONAL_NO_AUTORIZADO",
                        idEvento, usuario.id());
                throw new AccesoDenegadoException("PERSONAL_NO_AUTORIZADO",
                        "No tenés una asignación activa para validar entradas de este evento");
            }
        }
        // 3. Registro atómico del ingreso.
        IngresoResponseDTO ingreso = transactionTemplate.execute(
                status -> registrar(usuario, idEvento, codigoQr, excepcional));
        LOG.info("ingreso_registrado evento={} entrada={} usuario={} excepcional={}",
                idEvento, ingreso.idEntrada(), usuario.id(), excepcional);
        return ingreso;
    }

    private IngresoResponseDTO registrar(UsuarioActual usuario, String idEvento, UUID codigoQr,
                                         boolean excepcional) {
        Entrada entrada = entradaRepository.buscarPorCodigoQr(codigoQr)
                .orElseThrow(() -> new ResourceNotFoundException("No existe una entrada con ese código QR"));
        if (!entrada.getEvento().getIdEvento().equals(idEvento)) {
            throw new BusinessConflictException("ENTRADA_DE_OTRO_EVENTO",
                    "La entrada corresponde a otro evento");
        }

        controlEventoRepository.asegurar(idEvento);
        if (controlEventoRepository.bloquearCompartido(idEvento)) {
            throw new BusinessConflictException("EVENTO_CERRADO",
                    "El evento está cerrado: no se validan entradas");
        }

        int actualizadas = entradaRepository.cambiarEstado(entrada.getId(), EstadoEntrada.VIGENTE,
                EstadoEntrada.UTILIZADA, LocalDateTime.now(ZoneOffset.UTC));
        if (actualizadas == 0) {
            throw rechazoPorEstado(entrada.getId());
        }

        Ingreso ingreso = ingresoRepository.saveAndFlush(new Ingreso(entrada.getId(), idEvento, usuario.id(),
                excepcional, Instant.now(clock)));
        return new IngresoResponseDTO(
                ingreso.getId(),
                entrada.getId(),
                idEvento,
                new SectorResumenDTO(entrada.getSector().getIdSector(), entrada.getSector().getNombre()),
                entrada.getFila(),
                entrada.getNumero(),
                ingreso.getValidadoPor(),
                ingreso.isExcepcional(),
                ingreso.getValidadoEl());
    }

    /** La actualización condicional no aplicó: otra operación cambió el estado antes. */
    private BusinessConflictException rechazoPorEstado(UUID idEntrada) {
        Entrada actual = entradaRepository.buscarConDetalle(idEntrada).orElseThrow();
        if (actual.getEstado() == EstadoEntrada.UTILIZADA) {
            String cuando = ingresoRepository.findByIdEntrada(idEntrada)
                    .map(i -> " el " + i.getValidadoEl())
                    .orElse("");
            return new BusinessConflictException("ENTRADA_YA_UTILIZADA",
                    "La entrada ya se utilizó para ingresar" + cuando);
        }
        return new BusinessConflictException("ENTRADA_NO_HABILITADA",
                "La entrada no está habilitada para ingresar (" + actual.getEstado() + ")");
    }
}
