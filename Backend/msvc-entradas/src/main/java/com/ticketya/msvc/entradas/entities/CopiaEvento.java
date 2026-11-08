package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Copia mínima y de solo lectura de datos del evento (propiedad del servicio de eventos). */
@Entity
@Table(name = "copia_evento")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CopiaEvento {

    @Id
    @Column(name = "id_evento", length = 64)
    private String idEvento;

    @Column(nullable = false)
    private String nombre;

    @Column(name = "comienza_en", nullable = false)
    private LocalDateTime comienzaEn;
}
