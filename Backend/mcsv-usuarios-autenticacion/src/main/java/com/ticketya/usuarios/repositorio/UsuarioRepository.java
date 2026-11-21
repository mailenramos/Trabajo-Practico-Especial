package com.ticketya.usuarios.repositorio;

import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.modelo.Usuario;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID>, JpaSpecificationExecutor<Usuario> {

    Optional<Usuario> findByCorreo(String correo);

    @Query("select count(u) > 0 from Usuario u where u.activo = true and :rol member of u.roles")
    boolean existeActivoConRol(@Param("rol") Rol rol);

    /**
     * Bloquea a los usuarios activos con el rol indicado. Se usa para que dos administradores
     * no puedan quitarse el rol ADMIN a la vez y dejar el sistema sin administradores.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.activo = true and :rol member of u.roles")
    List<Usuario> bloquearActivosConRol(@Param("rol") Rol rol);
}
