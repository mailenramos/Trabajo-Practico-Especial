package com.ticketya.msvc.entradas.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "entradas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entrada {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "codigo_qr", nullable = false, unique = true, updatable = false)
    private UUID codigoQr;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_solicitud_emision", nullable = false, updatable = false)
    private SolicitudEmision solicitudEmision;

    @Column(name = "id_compra", nullable = false, updatable = false)
    private UUID idCompra;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_evento", nullable = false, updatable = false)
    private CopiaEvento evento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_sector", nullable = false, updatable = false)
    private CopiaSector sector;

    /** Nulo en sectores no numerados. */
    @Column(updatable = false, length = 50)
    private String fila;

    /** Nulo en sectores no numerados. */
    @Column(updatable = false)
    private Integer numero;

    @Column(name = "id_cliente", nullable = false, updatable = false, length = 64)
    private String idCliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoEntrada estado;

    @Column(name = "creado_el", nullable = false, updatable = false)
    private LocalDateTime creadoEl;

    @Column(name = "actualizado_el", nullable = false)
    private LocalDateTime actualizadoEl;

    /** Crea una entrada nueva: estado VIGENTE y un código QR aleatorio e impredecible. */
    public Entrada(SolicitudEmision solicitudEmision, UUID idCompra, CopiaEvento evento,
                   CopiaSector sector, String fila, Integer numero, String idCliente) {
        this.solicitudEmision = solicitudEmision;
        this.idCompra = idCompra;
        this.evento = evento;
        this.sector = sector;
        this.fila = fila;
        this.numero = numero;
        this.idCliente = idCliente;
        this.codigoQr = UUID.randomUUID();
        this.estado = EstadoEntrada.VIGENTE;
    }

    @PrePersist
    void antesDeGuardar() {
        LocalDateTime ahora = LocalDateTime.now(ZoneOffset.UTC);
        this.creadoEl = ahora;
        this.actualizadoEl = ahora;
    }

    @PreUpdate
    void antesDeActualizar() {
        this.actualizadoEl = LocalDateTime.now(ZoneOffset.UTC);
    }
}
