package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.ControlEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Coordinación local por evento: emisión y validación toman un bloqueo compartido (FOR SHARE)
 * y pueden ejecutarse a la vez; el cierre toma un bloqueo exclusivo (FOR UPDATE), espera a las
 * operaciones en curso y hace que las posteriores vean el evento cerrado.
 * Los métodos de bloqueo deben llamarse dentro de una transacción.
 */
public interface ControlEventoRepository extends JpaRepository<ControlEvento, String> {

    /** Crea la fila de control si no existe. Es seguro ante llamadas simultáneas. */
    @Modifying
    @Query(value = "insert into control_eventos (id_evento) values (:idEvento) "
            + "on conflict (id_evento) do nothing", nativeQuery = true)
    void asegurar(@Param("idEvento") String idEvento);

    /** Bloqueo compartido; devuelve true si el evento está cerrado. */
    @Query(value = "select cerrado_el is not null from control_eventos "
            + "where id_evento = :idEvento for share", nativeQuery = true)
    boolean bloquearCompartido(@Param("idEvento") String idEvento);

    /** Bloqueo exclusivo; devuelve true si el evento ya estaba cerrado. */
    @Query(value = "select cerrado_el is not null from control_eventos "
            + "where id_evento = :idEvento for update", nativeQuery = true)
    boolean bloquearExclusivo(@Param("idEvento") String idEvento);

    /** Registra el cierre si todavía no estaba registrado. */
    @Modifying
    @Query(value = "update control_eventos set cerrado_el = :ahora "
            + "where id_evento = :idEvento and cerrado_el is null", nativeQuery = true)
    int cerrar(@Param("idEvento") String idEvento, @Param("ahora") Instant ahora);
}
