package ticketya.error;

import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ticketya.web.dto.AvisoPedido;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Formato de error {timestamp, status, error, mensaje, campos?} con un controlador de prueba. */
class ManejadorDeErroresTest {

    MockMvc mvc;

    @RestController
    static class ControladorDePrueba {

        @PostMapping("/validar")
        void validar(@Valid @RequestBody AvisoPedido aviso) {
        }

        @PostMapping("/negocio")
        void negocio() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ESQUEMA_NO_SOPORTADO", "Versión no soportada");
        }

        @PostMapping("/romper")
        void romper() {
            throw new IllegalStateException("detalle interno");
        }
    }

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new ControladorDePrueba())
                .setControllerAdvice(new ManejadorDeErrores())
                .build();
    }

    @Test
    void errorDeValidacionConCampos() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acontecimientoId\":\"ac-1\",\"tipo\":\"OTRO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.campos.tipo").value("tipo de notificación desconocido"))
                .andExpect(jsonPath("$.campos.usuarioId").value("es obligatorio"))
                .andExpect(jsonPath("$.campos.acontecimientoId").doesNotExist());
    }

    @Test
    void errorDeNegocioSinCampos() throws Exception {
        mvc.perform(post("/negocio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ESQUEMA_NO_SOPORTADO"))
                .andExpect(jsonPath("$.mensaje").value("Versión no soportada"))
                .andExpect(jsonPath("$.campos").doesNotExist());
    }

    @Test
    void jsonIlegible() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CUERPO_INVALIDO"));
    }

    @Test
    void errorInesperadoNoExponeElDetalle() throws Exception {
        mvc.perform(post("/romper"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.mensaje").value("Ocurrió un error inesperado"));
    }
}
