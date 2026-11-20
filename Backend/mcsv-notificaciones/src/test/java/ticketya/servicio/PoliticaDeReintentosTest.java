package ticketya.servicio;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PoliticaDeReintentosTest {

    final PoliticaDeReintentos politica = new PoliticaDeReintentos(5, Duration.ofSeconds(30), Duration.ofMinutes(3));

    @Test
    void laEsperaCreceDeFormaExponencialHastaElTope() {
        assertThat(politica.esperaTras(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(politica.esperaTras(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(politica.esperaTras(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(politica.esperaTras(4)).isEqualTo(Duration.ofMinutes(3));
        assertThat(politica.esperaTras(50)).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void seAgotanAlLlegarAlMaximo() {
        assertThat(politica.agotados(4)).isFalse();
        assertThat(politica.agotados(5)).isTrue();
        assertThat(politica.agotados(6)).isTrue();
    }
}
