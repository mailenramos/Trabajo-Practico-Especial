package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.CopiaEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CopiaEventoRepository extends JpaRepository<CopiaEvento, String> {

    /** Inserta la copia si no existe. Es seguro ante dos emisiones simultáneas del mismo evento. */
    @Modifying
    @Query(value = "insert into copia_evento (id_evento, nombre, comienza_en) "
            + "values (:id, :nombre, :comienzaEn) on conflict (id_evento) do nothing",
            nativeQuery = true)
    void insertarSiNoExiste(@Param("id") String id,
                            @Param("nombre") String nombre,
                            @Param("comienzaEn") LocalDateTime comienzaEn);
}
