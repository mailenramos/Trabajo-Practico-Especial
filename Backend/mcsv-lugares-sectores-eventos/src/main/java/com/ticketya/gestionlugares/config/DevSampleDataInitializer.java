package com.ticketya.gestionlugares.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.EventoSector;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.domain.TipoSector;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;

@Component
@Profile("dev")
public class DevSampleDataInitializer implements ApplicationRunner {

        private final LugarRepository lugarRepository;
        private final EventoRepository eventoRepository;

        public DevSampleDataInitializer(LugarRepository lugarRepository, EventoRepository eventoRepository) {
                this.lugarRepository = lugarRepository;
                this.eventoRepository = eventoRepository;
        }

        @Override
        public void run(ApplicationArguments args) {
                List<Lugar> lugaresDeEjemplo = lugaresDeEjemplo();
                for (Lugar lugar : lugaresDeEjemplo) {
                        if (!lugarRepository.existsById(lugar.getId())) {
                                lugarRepository.save(lugar);
                        }
                }

                /*Lugar teatro = buscarLugarEjemplo(lugaresDeEjemplo.get(0).getId());*/
                Lugar teatro = buscarLugarEjemplo(lugaresDeEjemplo.get(0));
                /*Lugar estadio = buscarLugarEjemplo(lugaresDeEjemplo.get(1).getId());*/
                Lugar estadio = buscarLugarEjemplo(lugaresDeEjemplo.get(1));
                Instant ahora = Instant.now();
                for (Evento evento : eventosDeEjemplo(teatro, estadio, ahora)) {
                        if (!eventoRepository.existsById(evento.getId())) {
                                eventoRepository.save(evento);
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

        /*
         * private Lugar buscarLugarEjemplo(String lugarId) {
         * return lugarRepository.findById(lugarId)
         * .orElseThrow(() -> new
         * IllegalStateException("No se pudo cargar el lugar de ejemplo " + lugarId));
         * }
         */

        private Lugar buscarLugarEjemplo(Lugar lugarEjemplo) {
                return lugarRepository.findById(lugarEjemplo.getId()).orElse(lugarEjemplo);
        }

        private List<Evento> eventosDeEjemplo(Lugar teatro, Lugar estadio, Instant ahora) {
                return List.of(
                                crearEvento("672a1f77bcf86cd799439001", estadio, "Festival de Rock Nacional",
                                                "Una noche de rock nacional en Córdoba.", "MUSICA",
                                                List.of("Banda Alfa", "Banda Beta"), fechaUtc(ahora, 30),
                                                EstadoEvento.PUBLICADO,
                                                Map.of("Tribuna Norte", precio("20000"), "Campo de pie",
                                                                precio("8000"))),
                                crearEvento("672a1f77bcf86cd799439002", teatro, "El zorzal",
                                                "Una obra para recordar la voz de un clásico.", "TEATRO",
                                                List.of("Compañía Gamma"), fechaUtc(ahora, 60),
                                                EstadoEvento.PUBLICADO,
                                                Map.of("Platea", precio("12000"), "Pullman", precio("7000"))),
                                crearEvento("672a1f77bcf86cd799439003", estadio, "Final del Torneo",
                                                "La final que define al campeón.", "DEPORTE",
                                                List.of("Club Delta", "Club Epsilon"), fechaUtc(ahora, 90),
                                                EstadoEvento.AGOTADO,
                                                Map.of("Tribuna Norte", precio("30000"), "Campo de pie",
                                                                precio("12000"))),
                                crearEvento("672a1f77bcf86cd799439004", teatro, "Conferencia de Tecnología",
                                                "Un encuentro para explorar nuevas ideas tecnológicas.", "CONFERENCIA",
                                                List.of("Dra. Zeta"), fechaUtc(ahora, 30),
                                                EstadoEvento.BORRADOR, Map.of()),
                                crearEventoCancelado(estadio, fechaUtc(ahora, 60), ahora));
        }

        private Evento crearEvento(String id, Lugar lugar, String nombre, String descripcion,
                        String tipo, List<String> participantes, Instant fechaHora, EstadoEvento estado,
                        Map<String, BigDecimal> preciosPorSector) {
                List<EventoSector> sectores = new ArrayList<>();
                for (Sector sector : lugar.getSectores()) {
                        sectores.add(new EventoSector(sector.id(), sector.nombre(), sector.tipo(),
                                        sector.filas(), sector.asientosPorFila(), sector.capacidad(),
                                        preciosPorSector.get(sector.nombre())));
                }

                Evento evento = Evento.borrador("organizador-42", lugar.getId(), lugar.getCiudad(),
                                nombre, descripcion, tipo, participantes, fechaHora, 4, sectores);
                evento.setId(id);
                evento.setEstado(estado);
                if (estado == EstadoEvento.PUBLICADO || estado == EstadoEvento.AGOTADO
                                || estado == EstadoEvento.CANCELADO) {
                        evento.setPrecioMinimo(sectores.stream().map(EventoSector::getPrecio)
                                        .min(BigDecimal::compareTo).orElseThrow());
                }
                return evento;
        }

        private Evento crearEventoCancelado(Lugar estadio, Instant fechaHora, Instant ahora) {
                Evento evento = crearEvento("672a1f77bcf86cd799439005", estadio,
                                "Recital Cancelado", "Un recital reprogramado por motivos de producción.",
                                "MUSICA", List.of("Banda Eta"), fechaHora, EstadoEvento.CANCELADO,
                                Map.of("Tribuna Norte", precio("20000"), "Campo de pie", precio("8000")));
                evento.setMotivoCancelacion("Cancelado por motivos de producción.");
                evento.setCanceladoEn(ahora);
                evento.setAvisoCancelacionPendiente(false);
                return evento;
        }

        private Instant fechaUtc(Instant ahora, int dias) {
                return LocalDateTime.ofInstant(ahora, ZoneOffset.UTC).plusDays(dias)
                                .withHour(21).withMinute(0).withSecond(0).withNano(0)
                                .toInstant(ZoneOffset.UTC);
        }

        private BigDecimal precio(String valor) {
                return new BigDecimal(valor);
        }
}
