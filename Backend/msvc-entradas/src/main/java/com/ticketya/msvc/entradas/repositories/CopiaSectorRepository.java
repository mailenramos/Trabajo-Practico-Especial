package com.ticketya.msvc.entradas.repositories;

import com.ticketya.msvc.entradas.entities.CopiaSector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopiaSectorRepository extends JpaRepository<CopiaSector, String> {

    /** Inserta la copia si no existe. Es seguro ante dos emisiones simultáneas del mismo sector. */
    @Modifying
    @Query(value = "insert into copia_sector (id_sector, nombre) "
            + "values (:id, :nombre) on conflict (id_sector) do nothing",
            nativeQuery = true)
    void insertarSiNoExiste(@Param("id") String id, @Param("nombre") String nombre);
}
