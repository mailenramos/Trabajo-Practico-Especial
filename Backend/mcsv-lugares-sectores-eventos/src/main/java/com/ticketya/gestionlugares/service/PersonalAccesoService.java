package com.ticketya.gestionlugares.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.PersonalAccesoRepository;
import com.ticketya.gestionlugares.security.UserRoles;
import com.ticketya.gestionlugares.usuarios.http.HttpUsuariosClient;

@Service
public class PersonalAccesoService {
    private final EventoRepository eventos;
    private final PersonalAccesoRepository personal;
    private final HttpUsuariosClient usuarios;
    private final Clock clock;

    public PersonalAccesoService(EventoRepository eventos, PersonalAccesoRepository personal,
            HttpUsuariosClient usuarios, Clock clock) {
        this.eventos = eventos;
        this.personal = personal;
        this.usuarios = usuarios;
        this.clock = clock;
    }

    public AsignacionPersonalAcceso asignar(String eventoId, String correo, String userId, String userRoles) {
        autorizar(eventoId, userId, userRoles);
        HttpUsuariosClient.Usuario usuario = usuarios.consultarPorCorreo(correo);
        if (!usuario.activo() || !usuario.roles().contains("PERSONAL_ACCESO")) {
            throw new IllegalArgumentException("El usuario debe estar activo y tener el rol PERSONAL_ACCESO");
        }
        Instant ahora = clock.instant();
        var reactivada = personal.reactivarSiRevocada(eventoId, usuario.id(), ahora);
        if (reactivada.isPresent()) return reactivada.get();
        try {
            return personal.insert(new AsignacionPersonalAcceso(eventoId, usuario.id(), ahora));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("El usuario ya está asignado al evento");
        }
    }

    public List<AsignacionPersonalAcceso> listar(String eventoId, String userId, String userRoles) {
        autorizar(eventoId, userId, userRoles);
        return personal.findByEventoId(eventoId);
    }

    public void revocar(String eventoId, String usuarioId, String userId, String userRoles) {
        autorizar(eventoId, userId, userRoles);
        if (personal.revocarSiActiva(eventoId, usuarioId, clock.instant()).isEmpty()
                && personal.findByEventoIdAndUsuarioId(eventoId, usuarioId).isEmpty()) {
            throw new ResourceNotFoundException("Asignación no encontrada");
        }
    }

    private void autorizar(String eventoId, String userId, String userRoles) {
        Evento evento = eventos.findById(eventoId)
                .orElseThrow(() -> new ResourceNotFoundException("Evento no encontrado"));
        UserRoles roles = UserRoles.fromHeader(userRoles);
        if (userId == null || userId.isBlank()
                || !(roles.contains("ADMIN") || (roles.contains("ORGANIZADOR")
                        && userId.equals(evento.getOrganizadorId())))) {
            throw new ForbiddenException("Solo el organizador titular o un administrador puede gestionar el personal");
        }
    }
}
