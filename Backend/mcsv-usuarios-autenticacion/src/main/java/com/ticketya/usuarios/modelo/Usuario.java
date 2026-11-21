package com.ticketya.usuarios.modelo;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 100)
    private String apellido;

    /** Correo normalizado: es el identificador de acceso y no se edita. */
    @Column(nullable = false, length = 254, unique = true, updatable = false)
    private String correo;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean activo;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "usuario_roles", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "rol", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Set<Rol> roles = EnumSet.noneOf(Rol.class);

    @Column(name = "creado_el", nullable = false, updatable = false)
    private Instant creadoEl;

    @Column(name = "actualizado_el", nullable = false)
    private Instant actualizadoEl;

    @Version
    private long version;

    protected Usuario() {
    }

    public Usuario(UUID id, String nombre, String apellido, String correo, String passwordHash,
                   Collection<Rol> roles, Instant ahora) {
        this.id = id;
        this.nombre = nombre;
        this.apellido = apellido;
        this.correo = correo;
        this.passwordHash = passwordHash;
        this.activo = true;
        this.roles = EnumSet.copyOf(roles);
        this.creadoEl = ahora;
        this.actualizadoEl = ahora;
    }

    public void actualizarPerfil(String nombre, String apellido, Instant ahora) {
        this.nombre = nombre;
        this.apellido = apellido;
        this.actualizadoEl = ahora;
    }

    public void reemplazarRoles(Collection<Rol> nuevos, Instant ahora) {
        this.roles.clear();
        this.roles.addAll(nuevos);
        this.actualizadoEl = ahora;
    }

    public void agregarRol(Rol rol, Instant ahora) {
        this.roles.add(rol);
        this.actualizadoEl = ahora;
    }

    public void cambiarEstado(boolean activo, Instant ahora) {
        this.activo = activo;
        this.actualizadoEl = ahora;
    }

    public boolean tieneRol(Rol rol) {
        return roles.contains(rol);
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getApellido() {
        return apellido;
    }

    public String getCorreo() {
        return correo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isActivo() {
        return activo;
    }

    public Set<Rol> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getCreadoEl() {
        return creadoEl;
    }

    public Instant getActualizadoEl() {
        return actualizadoEl;
    }
}
