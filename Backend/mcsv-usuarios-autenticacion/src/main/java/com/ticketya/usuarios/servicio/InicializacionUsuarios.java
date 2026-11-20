package com.ticketya.usuarios.servicio;

import com.ticketya.usuarios.config.AdminInicialProperties;
import com.ticketya.usuarios.config.DatosPruebaProperties;
import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import com.ticketya.usuarios.repositorio.UsuarioRepository;
import com.ticketya.usuarios.web.dto.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Al arrancar crea el administrador inicial (solo si no hay ningún ADMIN activo) y, si están
 * habilitados, un usuario de prueba por cada rol. Ninguna credencial se escribe en el log.
 */
@Component
public class InicializacionUsuarios implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InicializacionUsuarios.class);
    private static final Pattern PASSWORD_VALIDA = Pattern.compile(Dtos.REGEX_PASSWORD);

    /** Contraseña común de los usuarios de prueba (solo desarrollo; ver README). */
    static final String PASSWORD_PRUEBA = "Prueba123";

    /** Usuarios de prueba con identificadores fijos, para usarlos desde Postman y otros servicios. */
    static final List<UsuarioPrueba> USUARIOS_PRUEBA = List.of(
            new UsuarioPrueba(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                    "Cliente", "Prueba", "cliente@ticketya.test", Rol.CLIENTE),
            new UsuarioPrueba(UUID.fromString("00000000-0000-4000-8000-000000000002"),
                    "Organizador", "Prueba", "organizador@ticketya.test", Rol.ORGANIZADOR),
            new UsuarioPrueba(UUID.fromString("00000000-0000-4000-8000-000000000003"),
                    "Admin", "Prueba", "admin@ticketya.test", Rol.ADMIN),
            new UsuarioPrueba(UUID.fromString("00000000-0000-4000-8000-000000000004"),
                    "Personal", "Prueba", "personal@ticketya.test", Rol.PERSONAL_ACCESO));

    private final UsuarioRepository repositorio;
    private final PasswordEncoder passwordEncoder;
    private final AdminInicialProperties admin;
    private final DatosPruebaProperties datosPrueba;
    private final Clock clock;

    public InicializacionUsuarios(UsuarioRepository repositorio, PasswordEncoder passwordEncoder,
                                  AdminInicialProperties admin, DatosPruebaProperties datosPrueba, Clock clock) {
        this.repositorio = repositorio;
        this.passwordEncoder = passwordEncoder;
        this.admin = admin;
        this.datosPrueba = datosPrueba;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        crearAdminInicial();
        if (datosPrueba.habilitados()) {
            crearUsuariosPrueba();
        }
    }

    void crearAdminInicial() {
        if (repositorio.existeActivoConRol(Rol.ADMIN)) {
            LOG.info("admin_inicial resultado=OMITIDO motivo=ya_existe_un_admin_activo");
            return;
        }
        String correo = Dtos.normalizarCorreo(admin.email());
        if (!PASSWORD_VALIDA.matcher(admin.password()).matches()) {
            throw new IllegalStateException("ADMIN_PASSWORD no cumple los requisitos mínimos: " + Dtos.MENSAJE_PASSWORD);
        }
        repositorio.findByCorreo(correo).ifPresentOrElse(
                existente -> {
                    // El correo ya estaba registrado: se le da el rol ADMIN y se activa, sin tocar su contraseña.
                    existente.agregarRol(Rol.ADMIN, clock.instant());
                    existente.cambiarEstado(true, clock.instant());
                    LOG.info("admin_inicial resultado=ROL_ASIGNADO usuarioId={}", existente.getId());
                },
                () -> {
                    Usuario nuevo = new Usuario(UUID.randomUUID(), admin.nombre(), admin.apellido(), correo,
                            passwordEncoder.encode(admin.password()), Set.of(Rol.ADMIN), clock.instant());
                    repositorio.save(nuevo);
                    LOG.info("admin_inicial resultado=CREADO usuarioId={}", nuevo.getId());
                });
    }

    void crearUsuariosPrueba() {
        for (UsuarioPrueba prueba : USUARIOS_PRUEBA) {
            if (repositorio.existsById(prueba.id()) || repositorio.findByCorreo(prueba.correo()).isPresent()) {
                continue;
            }
            repositorio.save(new Usuario(prueba.id(), prueba.nombre(), prueba.apellido(), prueba.correo(),
                    passwordEncoder.encode(PASSWORD_PRUEBA), Set.of(prueba.rol()), clock.instant()));
            LOG.info("datos_prueba usuario_creado usuarioId={} rol={}", prueba.id(), prueba.rol());
        }
    }

    record UsuarioPrueba(UUID id, String nombre, String apellido, String correo, Rol rol) {
    }
}
