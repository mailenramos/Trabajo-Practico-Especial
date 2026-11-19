package com.ticketya.preferencias.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketya.preferencias.api.ActualizarPreferenciasRequest;
import com.ticketya.preferencias.domain.Preferencia;
import com.ticketya.preferencias.repository.PreferenciasRepository;

import static org.mockito.Mockito.mock;

class PreferenciasServiceTest {
    private final PreferenciasRepository preferenciasRepository = mock(PreferenciasRepository.class);
    private final AtomicReference<Preferencia> persistida = new AtomicReference<>();
    private PreferenciasService preferenciasService;

    @BeforeEach
    void configurarRepositorio() {
        persistida.set(null);
        when(preferenciasRepository.save(any())).thenAnswer(invocacion -> {
            Preferencia preferencia = invocacion.getArgument(0);
            persistida.set(preferencia);
            return preferencia;
        });
        preferenciasService = new PreferenciasService(preferenciasRepository);
    }

    @Test
    void recortaValoresPermiteListasVaciasYActualizaFecha() {
        Instant inicio = Instant.now();

        Preferencia resultado = preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(
                        List.of("  Rock  "),
                        List.of(" Artista Uno "),
                        List.of(" Buenos Aires ")));

        assertEquals(List.of("Rock"), resultado.tiposEvento());
        assertEquals(List.of("Artista Uno"), resultado.artistas());
        assertEquals(List.of("Buenos Aires"), resultado.ciudades());
        assertNotNull(resultado.actualizadoEn());
        assertFalse(resultado.actualizadoEn().isBefore(inicio));

        Preferencia vacia = preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(List.of(), List.of(), List.of()));
        assertEquals(List.of(), vacia.tiposEvento());
        assertEquals(List.of(), vacia.artistas());
        assertEquals(List.of(), vacia.ciudades());
    }

    @Test
    void textoVacioNoReemplazaPreferenciasGuardadas() {
        Preferencia anterior = guardarPreferenciasValidas();

        assertThrows(IllegalArgumentException.class, () -> preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(List.of(" "), List.of(), List.of())));

        assertSame(anterior, persistida.get());
        verify(preferenciasRepository, times(1)).save(any());
    }

    @Test
    void valorRepetidoTrasRecortarNoReemplazaPreferenciasGuardadas() {
        Preferencia anterior = guardarPreferenciasValidas();

        assertThrows(IllegalArgumentException.class, () -> preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(List.of("Rock", " Rock "), List.of(), List.of())));

        assertSame(anterior, persistida.get());
        verify(preferenciasRepository, times(1)).save(any());
    }

    @Test
    void listaAusenteONulaNoReemplazaPreferenciasGuardadas() {
        Preferencia anterior = guardarPreferenciasValidas();

        assertThrows(IllegalArgumentException.class, () -> preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(null, List.of(), List.of())));

        assertSame(anterior, persistida.get());
        verify(preferenciasRepository, times(1)).save(any());
    }

    private Preferencia guardarPreferenciasValidas() {
        return preferenciasService.reemplazar("cliente-1",
                new ActualizarPreferenciasRequest(List.of("Rock"), List.of("Artista"), List.of("Rosario")));
    }
}
