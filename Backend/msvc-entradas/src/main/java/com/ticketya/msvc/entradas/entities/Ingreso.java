package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Ingreso registrado al validar el QR de una entrada. Hay como máximo uno por entrada. */
@Entity
@Table(name = "ingresos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ingreso {

    @Id
    private UUID id;

    @Column(name = "id_entrada", nullable = false, updatable = false, unique = true)
    private UUID idEntrada;

    @Column(name = "id_evento", nullable = false, updatable = false, length = 64)
    private String idEvento;

    @Column(name = "validado_por", nullable = false, updatable = false, length = 64)
    private String validadoPor;

    @Column(nullable = false, updatable = false)
    private boolean excepcional;

    @Column(name = "validado_el", nullable = false, updatable = false)
    private Instant validadoEl;

    public Ingreso(UUID idEntrada, String idEvento, String validadoPor, boolean excepcional, Instant validadoEl) {
        this.id = UUID.randomUUID();
        this.idEntrada = idEntrada;
        this.idEvento = idEvento;
        this.validadoPor = validadoPor;
        this.excepcional = excepcional;
        this.validadoEl = validadoEl;
    }
}
