package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    @SuppressWarnings("unchecked")
    void seSirveSinTokenYEsYamlValidoConElEndpointInterno() throws Exception {
        String yaml = mvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> documento = new Yaml().load(yaml);
        assertThat(documento).containsKey("openapi");
        Map<String, Object> rutas = (Map<String, Object>) documento.get("paths");
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) rutas.get("/internal/notificaciones")).get("post");
        assertThat(((Map<String, Object>) post.get("responses")).keySet()).contains("202", "400", "403");
        assertThat(rutas).containsKey("/actuator/health");
        Map<String, Object> componentes = (Map<String, Object>) documento.get("components");
        assertThat((Map<String, Object>) ((Map<String, Object>) componentes.get("securitySchemes")).get("TokenDeServicio"))
                .containsEntry("name", "X-Service-Token");
        assertThat(((Map<String, Object>) componentes.get("parameters")).get("XCorrelationId"))
                .isInstanceOf(Map.class);
    }
}
