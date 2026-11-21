package com.ticketya.usuarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketya.usuarios.repositorio.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base de los tests HTTP: levanta el servicio completo con H2 y los datos de prueba. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PruebaApi {

    protected static final String PASSWORD = "Secreta123";
    protected static final UUID ADMIN_PRUEBA = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected UsuarioRepository repositorio;

    protected static String correoUnico() {
        return "u-" + UUID.randomUUID() + "@ticketya.test";
    }

    protected ResultActions registrar(Map<String, Object> cuerpo) throws Exception {
        return mvc.perform(post("/auth/registro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cuerpo)));
    }

    /** Registra un cliente y devuelve su identificador. */
    protected UUID registrarCliente(String correo) throws Exception {
        String respuesta = registrar(Map.of("nombre", "Ana", "apellido", "Pérez",
                "correo", correo, "password", PASSWORD))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(respuesta).get("id").asText());
    }

    protected ResultActions login(String correo, String password) throws Exception {
        return mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("correo", correo, "password", password))));
    }

    protected JsonNode cuerpo(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    protected static MockHttpServletRequestBuilder como(MockHttpServletRequestBuilder pedido, UUID id, String roles) {
        return pedido.header("X-User-Id", id.toString()).header("X-User-Roles", roles);
    }

    protected MockHttpServletRequestBuilder conJson(MockHttpServletRequestBuilder pedido, Object cuerpo)
            throws Exception {
        return pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cuerpo));
    }
}
