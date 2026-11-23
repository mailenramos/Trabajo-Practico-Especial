package com.ticketya.usuarios;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocumentacionTest extends PruebaApi {
    @Test
    void documentoGeneradoCoincideConElContratoEnRutasSeguridadYRespuestas() throws Exception {
        JsonNode esperado = json.readTree(Files.readString(Path.of("docs/openapi-ms6.json")));
        JsonNode generado = json.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(generado).isEqualTo(esperado);
        assertThat(generado.path("components").path("securitySchemes"))
                .isEqualTo(esperado.path("components").path("securitySchemes"));
        assertThat(campos(generado.path("paths"))).isEqualTo(campos(esperado.path("paths")));
        esperado.path("paths").fields().forEachRemaining(path ->
                path.getValue().fields().forEachRemaining(method -> {
                    JsonNode real = generado.path("paths").path(path.getKey()).path(method.getKey());
                    assertThat(real.isMissingNode()).as(path.getKey() + " " + method.getKey()).isFalse();
                    assertThat(real.path("security").isMissingNode() ? json.createArrayNode() : real.path("security"))
                            .as("seguridad de " + path.getKey() + " " + method.getKey())
                            .isEqualTo(method.getValue().path("security").isMissingNode()
                                    ? json.createArrayNode() : method.getValue().path("security"));
                    assertThat(real.path("responses"))
                            .as("respuestas de " + path.getKey() + " " + method.getKey())
                            .isEqualTo(method.getValue().path("responses"));
                }));
    }

    private static Set<String> campos(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
