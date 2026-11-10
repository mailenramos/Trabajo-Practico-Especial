package com.ticketya.gestionlugares.config;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.LugarRepository;

@Component
@Profile("dev")
public class DevSampleDataInitializer implements ApplicationRunner {

    private final LugarRepository lugarRepository;

    public DevSampleDataInitializer(LugarRepository lugarRepository) {
        this.lugarRepository = lugarRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Lugar lugar : lugaresDeEjemplo()) {
            if (!lugarRepository.existsById(lugar.getId())) {
                lugarRepository.save(lugar);
            }
        }
    }

    private List<Lugar> lugaresDeEjemplo() {
        Lugar teatro = new Lugar("507f1f77bcf86cd799439011", "Teatro Central",
                "Av. Corrientes 1234", "Buenos Aires", -34.6037, -58.3816,
                List.of(
                        new Sector("550e8400-e29b-41d4-a716-446655440001", "Platea",
                                TipoSector.NUMERADO, 12, 20, null),
                        new Sector("550e8400-e29b-41d4-a716-446655440002", "Pullman",
                                TipoSector.NO_NUMERADO, null, null, 180)));

        Lugar estadio = new Lugar("507f1f77bcf86cd799439012", "Estadio del Parque",
                "Av. del Deporte 2500", "Córdoba", -31.4201, -64.1888,
                List.of(
                        new Sector("550e8400-e29b-41d4-a716-446655440003", "Tribuna Norte",
                                TipoSector.NUMERADO, 25, 40, null),
                        new Sector("550e8400-e29b-41d4-a716-446655440004", "Campo de pie",
                                TipoSector.NO_NUMERADO, null, null, 2500)));

        return List.of(teatro, estadio);
    }
}
