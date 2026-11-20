package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ticketya.modelo.ModificacionEvento;

import java.util.Optional;

public interface ModificacionEventoRepository extends JpaRepository<ModificacionEvento, Long> {

    boolean existsByEventoIdAndVersion(String eventoId, int version);

    /** La versión más alta ya registrada del evento, o vacío si nunca se modificó. */
    @Query("select max(m.version) from ModificacionEvento m where m.eventoId = :eventoId")
    Optional<Integer> maximaVersion(@Param("eventoId") String eventoId);
}
