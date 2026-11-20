package ticketya.repositorio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import ticketya.modelo.ModificacionEvento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Modificaciones de eventos: única por (evento, versión) y versión máxima por evento. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/ticketya_compras_test?createDatabaseIfNotExist=true&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"
})
class ModificacionEventoRepositoryTest {

    @Autowired
    ModificacionEventoRepository modificaciones;

    @BeforeEach
    void limpiar() {
        modificaciones.deleteAll();
    }

    @Test
    void laMismaVersionDelMismoEventoNoSePuedeGuardarDosVeces() {
        modificaciones.saveAndFlush(new ModificacionEvento("e-1", 1));

        assertThatThrownBy(() -> modificaciones.saveAndFlush(new ModificacionEvento("e-1", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(modificaciones.count()).isEqualTo(1);
    }

    @Test
    void otraVersionUOtroEventoSiSeGuardan() {
        modificaciones.saveAndFlush(new ModificacionEvento("e-1", 1));
        modificaciones.saveAndFlush(new ModificacionEvento("e-1", 2));
        modificaciones.saveAndFlush(new ModificacionEvento("e-2", 1));

        assertThat(modificaciones.count()).isEqualTo(3);
        assertThat(modificaciones.existsByEventoIdAndVersion("e-1", 2)).isTrue();
        assertThat(modificaciones.existsByEventoIdAndVersion("e-2", 2)).isFalse();
        assertThat(modificaciones.findAll()).allSatisfy(m -> assertThat(m.getRecibidaEn()).isNotNull());
    }

    @Test
    void laVersionMaximaEsPorEvento() {
        modificaciones.saveAndFlush(new ModificacionEvento("e-1", 3));
        modificaciones.saveAndFlush(new ModificacionEvento("e-1", 1));
        modificaciones.saveAndFlush(new ModificacionEvento("e-2", 7));

        assertThat(modificaciones.maximaVersion("e-1")).contains(3);
        assertThat(modificaciones.maximaVersion("e-2")).contains(7);
        assertThat(modificaciones.maximaVersion("e-3")).isEmpty();
    }
}
