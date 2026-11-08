package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Copia mínima y de solo lectura de datos del sector (propiedad del servicio de eventos). */
@Entity
@Table(name = "copia_sector")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CopiaSector {

    @Id
    @Column(name = "id_sector", length = 64)
    private String idSector;

    @Column(nullable = false)
    private String nombre;
}
