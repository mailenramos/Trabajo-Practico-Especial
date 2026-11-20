package com.ticketya.usuarios.servicio;

import com.ticketya.usuarios.error.ApiException;
import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import com.ticketya.usuarios.repositorio.UsuarioRepository;
import com.ticketya.usuarios.repositorio.UsuarioSpecs;
import com.ticketya.usuarios.web.UsuarioActual;
import com.ticketya.usuarios.web.dto.Dtos;
import com.ticketya.usuarios.web.dto.Dtos.ContactoRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.IdentidadRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.PaginaUsuariosRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.PerfilPedido;
import com.ticketya.usuarios.web.dto.Dtos.UsuarioRespuesta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.ticketya.usuarios.config.RequestIdFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class UsuarioService {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioService.class);

    private final UsuarioRepository repositorio;
    private final Clock clock;

    public UsuarioService(UsuarioRepository repositorio, Clock clock) {
        this.repositorio = repositorio;
        this.clock = clock;
    }

    // ---------- Perfil propio ----------

    @Transactional(readOnly = true)
    public UsuarioRespuesta perfil(UsuarioActual actual) {
        return UsuarioRespuesta.de(buscar(actual.id()));
    }

    /** Solo cambian nombre y apellido: roles, estado y correo no se editan desde el perfil. */
    @Transactional
    public UsuarioRespuesta actualizarPerfil(UsuarioActual actual, PerfilPedido pedido) {
        Usuario usuario = buscar(actual.id());
        usuario.actualizarPerfil(pedido.nombre(), pedido.apellido(), clock.instant());
        return UsuarioRespuesta.de(repositorio.saveAndFlush(usuario));
    }

    // ---------- Administración ----------

    @Transactional(readOnly = true)
    public PaginaUsuariosRespuesta listar(UsuarioActual actual, String rol, String correo, int page, int size) {
        exigirAdmin(actual);
        Rol filtroRol = null;
        if (rol != null && !rol.isBlank()) {
            filtroRol = Rol.desde(rol).orElseThrow(() -> rolInexistente("rol", rol));
        }
        Specification<Usuario> filtro = UsuarioSpecs.conRol(filtroRol)
                .and(UsuarioSpecs.correoContiene(correo));
        Page<Usuario> pagina = repositorio.findAll(filtro,
                PageRequest.of(page, size, Sort.by("correo").ascending()));
        return new PaginaUsuariosRespuesta(
                pagina.getContent().stream().map(UsuarioRespuesta::de).toList(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UsuarioRespuesta obtener(UsuarioActual actual, UUID id) {
        exigirAdmin(actual);
        return UsuarioRespuesta.de(buscar(id));
    }

    @Transactional
    public UsuarioRespuesta actualizarRoles(UsuarioActual actual, UUID id, List<String> nombresRoles) {
        exigirAdmin(actual);
        Set<Rol> nuevos = EnumSet.noneOf(Rol.class);
        for (String nombre : nombresRoles) {
            nuevos.add(Rol.desde(nombre).orElseThrow(() -> rolInexistente("roles", nombre)));
        }
        Usuario usuario = buscar(id);
        Set<Rol> anteriores = usuario.getRoles();
        if (usuario.isActivo() && anteriores.contains(Rol.ADMIN) && !nuevos.contains(Rol.ADMIN)) {
            exigirOtroAdminActivo(usuario, "No se puede quitar el rol ADMIN al único administrador activo");
        }
        usuario.reemplazarRoles(nuevos, clock.instant());
        repositorio.saveAndFlush(usuario);
        LOG.info("cambio_roles requestId={} adminId={} usuarioId={} rolesAnteriores={} rolesNuevos={}",
                MDC.get(RequestIdFilter.CLAVE_MDC), actual.id(), usuario.getId(),
                ordenados(anteriores), ordenados(nuevos));
        return UsuarioRespuesta.de(usuario);
    }

    /** Borrado lógico: desactivar no elimina la cuenta ni su historial. */
    @Transactional
    public UsuarioRespuesta cambiarEstado(UsuarioActual actual, UUID id, boolean activo) {
        exigirAdmin(actual);
        if (!activo && actual.id().equals(id)) {
            throw ApiException.conflicto("AUTODESACTIVACION", "Un administrador no puede desactivarse a sí mismo");
        }
        Usuario usuario = buscar(id);
        if (usuario.isActivo() == activo) {
            return UsuarioRespuesta.de(usuario);
        }
        if (!activo && usuario.tieneRol(Rol.ADMIN)) {
            exigirOtroAdminActivo(usuario, "No se puede desactivar al único administrador activo");
        }
        usuario.cambiarEstado(activo, clock.instant());
        repositorio.saveAndFlush(usuario);
        LOG.info("cambio_estado requestId={} adminId={} usuarioId={} activo={}",
                MDC.get(RequestIdFilter.CLAVE_MDC), actual.id(), usuario.getId(), activo);
        return UsuarioRespuesta.de(usuario);
    }

    // ---------- Consultas internas (otros microservicios) ----------

    /** Devuelve el correo aunque la cuenta esté inactiva, para avisos de operaciones ya hechas. */
    @Transactional(readOnly = true)
    public ContactoRespuesta contacto(UUID id) {
        return new ContactoRespuesta(buscar(id).getCorreo());
    }

    @Transactional(readOnly = true)
    public IdentidadRespuesta porCorreo(String correo) {
        String normalizado = Dtos.normalizarCorreo(correo);
        return repositorio.findByCorreo(normalizado)
                .map(IdentidadRespuesta::de)
                .orElseThrow(() -> ApiException.noEncontrado("No existe un usuario con ese correo"));
    }

    // ---------- Auxiliares ----------

    private Usuario buscar(UUID id) {
        return repositorio.findById(id)
                .orElseThrow(() -> ApiException.noEncontrado("No existe el usuario " + id));
    }

    private static void exigirAdmin(UsuarioActual actual) {
        if (!actual.esAdmin()) {
            throw ApiException.accesoDenegado("La operación requiere el rol ADMIN");
        }
    }

    private void exigirOtroAdminActivo(Usuario usuario, String mensaje) {
        List<Usuario> admins = repositorio.bloquearActivosConRol(Rol.ADMIN);
        boolean hayOtro = admins.stream().anyMatch(admin -> !admin.getId().equals(usuario.getId()));
        if (!hayOtro) {
            throw ApiException.conflicto("ULTIMO_ADMINISTRADOR", mensaje);
        }
    }

    private static ApiException rolInexistente(String campo, String valor) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put(campo, "rol inexistente: " + Optional.ofNullable(valor).orElse("null")
                + " (válidos: CLIENTE, ORGANIZADOR, ADMIN, PERSONAL_ACCESO)");
        return ApiException.validacion("Rol inexistente", campos);
    }

    private static List<String> ordenados(Set<Rol> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
