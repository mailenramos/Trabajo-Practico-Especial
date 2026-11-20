package com.ticketya.usuarios.servicio;

import com.ticketya.usuarios.error.ApiException;
import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import com.ticketya.usuarios.repositorio.UsuarioRepository;
import com.ticketya.usuarios.seguridad.TokenService;
import com.ticketya.usuarios.web.dto.Dtos.LoginPedido;
import com.ticketya.usuarios.web.dto.Dtos.RegistroPedido;
import com.ticketya.usuarios.web.dto.Dtos.TokenRespuesta;
import com.ticketya.usuarios.web.dto.Dtos.UsuarioRespuesta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AutenticacionService {

    private static final Logger LOG = LoggerFactory.getLogger(AutenticacionService.class);
    private static final String CREDENCIALES_INVALIDAS = "Correo o contraseña incorrectos";

    private final UsuarioRepository repositorio;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    /** Hash usado cuando el correo no existe, para que ambos casos tarden lo mismo. */
    private final String hashFicticio;

    public AutenticacionService(UsuarioRepository repositorio, PasswordEncoder passwordEncoder,
                                TokenService tokenService, Clock clock) {
        this.repositorio = repositorio;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Registro público: el usuario queda siempre activo y con rol CLIENTE. */
    @Transactional
    public UsuarioRespuesta registrar(RegistroPedido pedido) {
        if (repositorio.findByCorreo(pedido.correo()).isPresent()) {
            throw correoRegistrado();
        }
        Usuario usuario = new Usuario(UUID.randomUUID(), pedido.nombre(), pedido.apellido(), pedido.correo(),
                passwordEncoder.encode(pedido.password()), Set.of(Rol.CLIENTE), clock.instant());
        try {
            repositorio.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException ex) {
            // Otro registro simultáneo con el mismo correo ganó la restricción de unicidad.
            throw correoRegistrado();
        }
        LOG.info("registro_usuario usuarioId={} correo={}", usuario.getId(), usuario.getCorreo());
        return UsuarioRespuesta.de(usuario);
    }

    @Transactional(readOnly = true)
    public TokenRespuesta login(LoginPedido pedido) {
        Optional<Usuario> encontrado = repositorio.findByCorreo(pedido.correo());
        String hash = encontrado.map(Usuario::getPasswordHash).orElse(hashFicticio);
        boolean coincide = passwordEncoder.matches(pedido.password(), hash);

        if (encontrado.isEmpty() || !coincide) {
            LOG.warn("login correo={} resultado=CREDENCIALES_INVALIDAS", pedido.correo());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", CREDENCIALES_INVALIDAS);
        }
        Usuario usuario = encontrado.get();
        if (!usuario.isActivo()) {
            LOG.warn("login correo={} resultado=USUARIO_INACTIVO", pedido.correo());
            throw new ApiException(HttpStatus.FORBIDDEN, "USUARIO_INACTIVO", "La cuenta está desactivada");
        }
        TokenService.TokenEmitido emitido = tokenService.emitir(usuario);
        LOG.info("login correo={} usuarioId={} resultado=EXITOSO", pedido.correo(), usuario.getId());
        return new TokenRespuesta(emitido.token(), "Bearer", emitido.expiraEnSegundos());
    }

    private static ApiException correoRegistrado() {
        return ApiException.conflicto("CORREO_YA_REGISTRADO", "Ya existe un usuario con ese correo");
    }
}
