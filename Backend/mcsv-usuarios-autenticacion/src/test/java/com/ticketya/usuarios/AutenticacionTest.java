package com.ticketya.usuarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AutenticacionTest extends PruebaApi {

    private static final String SECRETO = "secreto-de-pruebas-con-al-menos-32-bytes";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registroCreaClienteActivoSinDevolverLaContrasena() throws Exception {
        String correo = correoUnico();
        JsonNode respuesta = cuerpo(registrar(Map.of("nombre", " Ana ", "apellido", "Pérez",
                "correo", correo, "password", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", contains("CLIENTE")))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist()));

        Usuario guardado = repositorio.findById(UUID.fromString(respuesta.get("id").asText())).orElseThrow();
        assertThat(guardado.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, guardado.getPasswordHash())).isTrue();
    }

    @Test
    void registroIgnoraElRolSolicitado() throws Exception {
        registrar(Map.of("nombre", "Eva", "apellido", "Gómez", "correo", correoUnico(),
                "password", PASSWORD, "roles", List.of("ADMIN"), "rol", "ORGANIZADOR", "activo", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", contains("CLIENTE")))
                .andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    void registroRechazaCorreoRepetidoAunqueCambienMayusculasYEspacios() throws Exception {
        String correo = correoUnico();
        registrarCliente(correo);

        registrar(Map.of("nombre", "Ana", "apellido", "Pérez",
                "correo", "  " + correo.toUpperCase() + " ", "password", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CORREO_YA_REGISTRADO"));
    }

    @Test
    void registroInformaLosCamposInvalidos() throws Exception {
        registrar(Map.of("nombre", "", "correo", "no-es-correo", "password", "corta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.nombre").exists())
                .andExpect(jsonPath("$.campos.apellido").exists())
                .andExpect(jsonPath("$.campos.correo").exists())
                .andExpect(jsonPath("$.campos.password").exists());
    }

    @Test
    void registroRechazaContrasenaSinNumero() throws Exception {
        registrar(Map.of("nombre", "Ana", "apellido", "Pérez", "correo", correoUnico(),
                "password", "solamenteletras"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.password").exists());
    }

    @Test
    void registrosSimultaneosConElMismoCorreoCreanUnSoloUsuario() throws Exception {
        String correo = correoUnico();
        int hilos = 6;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        try {
            List<Future<Integer>> resultados = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                Callable<Integer> tarea = () -> {
                    largada.await();
                    return registrar(Map.of("nombre", "Ana", "apellido", "Pérez",
                            "correo", correo, "password", PASSWORD))
                            .andReturn().getResponse().getStatus();
                };
                resultados.add(ejecutor.submit(tarea));
            }
            largada.countDown();
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> resultado : resultados) {
                estados.add(resultado.get());
            }
            assertThat(estados).filteredOn(e -> e == 201).hasSize(1);
            assertThat(estados).filteredOn(e -> e == 409).hasSize(hilos - 1);
            assertThat(repositorio.findByCorreo(correo)).isPresent();
        } finally {
            ejecutor.shutdownNow();
        }
    }

    @Test
    void loginEmiteJwtConLosClaimsAcordados() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);

        JsonNode respuesta = cuerpo(login(correo.toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoToken").value("Bearer"))
                .andExpect(jsonPath("$.expiraEn").value(3600)));

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(respuesta.get("token").asText())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.get("email", String.class)).isEqualTo(correo);
        assertThat(claims.get("roles", List.class)).containsExactly("CLIENTE");
        assertThat(claims.getIssuer()).isEqualTo("ticketya-usuarios");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(3_600_000L);
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iss", "email", "roles", "iat", "exp");
    }

    @Test
    void loginIncluyeTodosLosRolesDelUsuario() throws Exception {
        String correo = correoUnico();
        UUID id = registrarCliente(correo);
        Usuario usuario = repositorio.findById(id).orElseThrow();
        usuario.reemplazarRoles(List.of(Rol.CLIENTE, Rol.PERSONAL_ACCESO), usuario.getActualizadoEl());
        repositorio.saveAndFlush(usuario);

        String token = cuerpo(login(correo, PASSWORD)).get("token").asText();
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.get("roles", List.class)).containsExactly("CLIENTE", "PERSONAL_ACCESO");
    }

    @Test
    void loginFallidoNoRevelaSiFallaElCorreoOLaContrasena() throws Exception {
        String correo = correoUnico();
        registrarCliente(correo);

        JsonNode contrasenaIncorrecta = cuerpo(login(correo, "Otra12345").andExpect(status().isUnauthorized()));
        JsonNode correoInexistente = cuerpo(login(correoUnico(), PASSWORD).andExpect(status().isUnauthorized()));

        assertThat(contrasenaIncorrecta.get("error").asText()).isEqualTo("CREDENCIALES_INVALIDAS");
        assertThat(correoInexistente.get("error").asText()).isEqualTo("CREDENCIALES_INVALIDAS");
        assertThat(contrasenaIncorrecta.get("mensaje")).isEqualTo(correoInexistente.get("mensaje"));
    }

    @Test
    void loginSinDatosDevuelveErrorDeValidacion() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.correo").exists())
                .andExpect(jsonPath("$.campos.password").exists());
    }

    @Test
    void usuarioDePruebaPuedeIniciarSesion() throws Exception {
        login("organizador@ticketya.test", "Prueba123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", startsWith("ey")));
    }
}
