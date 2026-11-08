package com.ticketya.gestionlugares.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ticketya.gestionlugares.api.CreateLugarRequest;
import com.ticketya.gestionlugares.api.CreateSectorRequest;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.LugarRepository;

@ExtendWith(MockitoExtension.class)
class LugarServiceTest {

        @Mock
        private LugarRepository lugarRepository;

        private LugarService lugarService;

        @BeforeEach
        void setUp() {
                lugarService = new LugarService(lugarRepository);
                lenient().when(lugarRepository.save(any(Lugar.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        void creaLugarConIdObjectIdEnFormatoString() {
                Lugar creado = lugarService.crearLugar(new CreateLugarRequest(
                                " Teatro Central ", " Av. Corrientes 1234 ", "Buenos Aires", -34.6, -58.4));

                assertEquals(24, creado.getId().length());
                assertEquals("Teatro Central", creado.getNombre());
                verify(lugarRepository).save(any(Lugar.class));
        }

        @Test
        void creaSectorNumeradoConUuidStringInmutableEnElDocumento() {
                Lugar lugar = new Lugar("507f1f77bcf86cd799439011", "Teatro", "Calle 1", null,
                                -34.0, -58.0, new ArrayList<>());
                when(lugarRepository.findById(lugar.getId())).thenReturn(Optional.of(lugar));

                Sector creado = lugarService.agregarSector(lugar.getId(),
                                new CreateSectorRequest("Platea", TipoSector.NUMERADO, 2, 5, null));
                String uuidAlCrear = creado.id();

                assertNotNull(UUID.fromString(uuidAlCrear));
                assertEquals(uuidAlCrear, lugar.getSectores().getFirst().id());

                lugarService.agregarSector(lugar.getId(),
                                new CreateSectorRequest("Campo", TipoSector.NO_NUMERADO, null, null, 50));
                assertNotEquals(uuidAlCrear, lugar.getSectores().getLast().id());
                assertEquals(uuidAlCrear, lugar.getSectores().getFirst().id());
        }

        @Test
        void validaDimensionesYCapacidadDelTipoDeSector() {
                Lugar lugar = new Lugar("507f1f77bcf86cd799439011", "Teatro", "Calle 1", null,
                                -34.0, -58.0, new ArrayList<>());
                when(lugarRepository.findById(lugar.getId())).thenReturn(Optional.of(lugar));

                assertThrows(IllegalArgumentException.class, () -> lugarService.agregarSector(lugar.getId(),
                                new CreateSectorRequest("Platea", TipoSector.NUMERADO, 0, 5, null)));
                assertThrows(IllegalArgumentException.class, () -> lugarService.agregarSector(lugar.getId(),
                                new CreateSectorRequest("Campo", TipoSector.NO_NUMERADO, null, null, 0)));
        }

        @Test
        void creaSectorNoNumeradoConCapacidadYUuid() {
                Lugar lugar = new Lugar("507f1f77bcf86cd799439011", "Teatro", "Calle 1", null,
                                -34.0, -58.0, new ArrayList<>());
                when(lugarRepository.findById(lugar.getId())).thenReturn(Optional.of(lugar));

                Sector creado = lugarService.agregarSector(lugar.getId(),
                                new CreateSectorRequest("Campo", TipoSector.NO_NUMERADO, null, null, 25));

                assertEquals(TipoSector.NO_NUMERADO, creado.tipo());
                assertEquals(25, creado.capacidad());
                assertNotNull(UUID.fromString(creado.id()));
        }

        @Test
        void rechazaSectorDeLugarInexistente() {
                when(lugarRepository.findById("507f1f77bcf86cd799439011")).thenReturn(Optional.empty());

                assertThrows(ResourceNotFoundException.class, () -> lugarService.agregarSector(
                                "507f1f77bcf86cd799439011",
                                new CreateSectorRequest("Platea", TipoSector.NUMERADO, 2, 5, null)));
        }
}
