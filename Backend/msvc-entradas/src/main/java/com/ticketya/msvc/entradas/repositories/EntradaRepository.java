package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.Entrada;
import com.ticketya.msvc.entradas.entities.EstadoEntrada;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntradaRepository extends JpaRepository<Entrada, UUID>, JpaSpecificationExecutor<Entrada> {

    /** Listado con filtros opcionales; trae evento y sector en la misma consulta. */
    @Override
    @EntityGraph(attributePaths = {"evento", "sector"})
    Page<Entrada> findAll(Specification<Entrada> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"evento", "sector"})
    @Query("select e from Entrada e where e.id = :id")
    Optional<Entrada> buscarConDetalle(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"evento", "sector"})
    @Query("select e from Entrada e where e.idCompra = :idCompra order by e.creadoEl, e.id")
    List<Entrada> buscarPorCompra(@Param("idCompra") UUID idCompra);

    /**
     * Cambio de estado atómico y condicional: solo se aplica si la entrada sigue en el estado esperado.
     * Devuelve la cantidad de filas modificadas (0 si otra operación ya la cambió).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Entrada e set e.estado = :nuevo, e.actualizadoEl = :ahora "
            + "where e.id = :id and e.estado = :esperado")
    int cambiarEstado(@Param("id") UUID id,
                      @Param("esperado") EstadoEntrada esperado,
                      @Param("nuevo") EstadoEntrada nuevo,
                      @Param("ahora") LocalDateTime ahora);

    /** Igual que cambiarEstado, pero para todas las entradas de un evento que están en el estado esperado. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Entrada e set e.estado = :nuevo, e.actualizadoEl = :ahora "
            + "where e.evento.idEvento = :idEvento and e.estado = :esperado")
    int cambiarEstadoPorEvento(@Param("idEvento") String idEvento,
                               @Param("esperado") EstadoEntrada esperado,
                               @Param("nuevo") EstadoEntrada nuevo,
                               @Param("ahora") LocalDateTime ahora);
}
