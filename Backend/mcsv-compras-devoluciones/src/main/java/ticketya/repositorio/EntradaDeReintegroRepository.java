package ticketya.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import ticketya.modelo.EntradaDeReintegro;

import java.util.List;
import java.util.UUID;

public interface EntradaDeReintegroRepository extends JpaRepository<EntradaDeReintegro, Long> {

    List<EntradaDeReintegro> findByReintegroIdOrderByIdAsc(UUID reintegroId);

    List<EntradaDeReintegro> findByReintegroIdAndDevueltaFalseOrderByIdAsc(UUID reintegroId);
}
