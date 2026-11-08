package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "solicitudes_emision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SolicitudEmision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "id_compra", nullable = false, unique = true, updatable = false)
    private UUID idCompra;

    @Column(name = "creado_el", nullable = false, updatable = false)
    private LocalDateTime creadoEl;

    public SolicitudEmision(UUID idCompra) {
        this.idCompra = idCompra;
    }

    @PrePersist
    void antesDeGuardar() {
        this.creadoEl = LocalDateTime.now(ZoneOffset.UTC);
    }
}
