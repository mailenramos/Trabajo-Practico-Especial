package ticketya.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "notificaciones.reintentos.maximo-intentos=7",
        "notificaciones.usuarios.url=http://usuarios:9000"
})
@ActiveProfiles("test")
class NotificacionesPropertiesTest {

    @Autowired
    NotificacionesProperties propiedades;

    @Test
    void tomaLosValoresConfiguradosYLosValoresPorDefecto() {
        assertThat(propiedades.reintentos().maximoIntentos()).isEqualTo(7);
        assertThat(propiedades.usuarios().url()).isEqualTo("http://usuarios:9000");
        assertThat(propiedades.usuarios().timeoutConexion()).isEqualTo(Duration.ofSeconds(2));
        assertThat(propiedades.usuarios().timeoutLectura()).isEqualTo(Duration.ofSeconds(5));
        assertThat(propiedades.reintentos().esperaBase()).isEqualTo(Duration.ofSeconds(30));
        assertThat(propiedades.envio().plazoProcesamiento()).isEqualTo(Duration.ofMinutes(2));
        assertThat(propiedades.correo().remitente()).isNotBlank();
    }
}
