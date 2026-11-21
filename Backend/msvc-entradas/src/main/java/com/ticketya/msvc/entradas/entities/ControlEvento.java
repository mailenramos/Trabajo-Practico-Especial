package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Fila de control de un evento. Se crea con un insert idempotente y se bloquea para coordinar
 * emisión, validación y cierre (ver ControlEventoRepository).
 */
@Entity
@Table(name = "control_eventos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ControlEvento {

    @Id
    @Column(name = "id_evento", length = 64)
    private String idEvento;

    /** Nulo mientras el evento esté abierto. */
    @Column(name = "cerrado_el")
    private Instant cerradoEl;

    @Column(name = "creado_el", nullable = false, insertable = false, updatable = false)
    private Instant creadoEl;
}
