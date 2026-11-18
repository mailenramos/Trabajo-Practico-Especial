package com.ticketya.gestionlugares.outbox;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.ticketya.gestionlugares.compras.http.ComprasProperties;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.repository.OutboxAvisoRepository;

@Component
public class EventoCancelacionOutboxBackfill implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventoCancelacionOutboxBackfill.class);
    private static final String COLECCION_EVENTOS = "eventos";
    private static final String CAMPO_AVISO_LEGACY = "avisoCancelacionPendiente";

    private final MongoTemplate mongoTemplate;
    private final OutboxAvisoRepository outboxRepository;
    private final ComprasProperties comprasProperties;
    private final Clock clock;

    public EventoCancelacionOutboxBackfill(MongoTemplate mongoTemplate,
            OutboxAvisoRepository outboxRepository, ComprasProperties comprasProperties, Clock outboxClock) {
        this.mongoTemplate = mongoTemplate;
        this.outboxRepository = outboxRepository;
        this.comprasProperties = comprasProperties;
        this.clock = outboxClock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Resultado resultado = migrarPendientes();
        LOGGER.info("Backfill de cancelaciones: migrados={}, ya existentes={}, omitidos={}",
                resultado.migrados(), resultado.yaExistian(), resultado.omitidos());
    }

    public Resultado migrarPendientes() {
        int migrados = 0;
        int yaExistian = 0;
        int omitidos = 0;

        Query eventosConAvisoLegacy = Query.query(Criteria.where(CAMPO_AVISO_LEGACY).is(true));
        for (Evento evento : mongoTemplate.find(eventosConAvisoLegacy, Evento.class, COLECCION_EVENTOS)) {
            String inconsistencia = inconsistencia(evento);
            if (inconsistencia != null) {
                LOGGER.warn("Se omite el backfill del evento {}: {}", evento.getId(), inconsistencia);
                omitidos++;
                continue;
            }

            var ahora = clock.instant();
            try {
                outboxRepository.insert(CancelacionAvisoOutboxFactory.crear(
                        evento.getId(), evento.getMotivoCancelacion(), evento.getCanceladoEn(),
                        ahora, comprasProperties));
                migrados++;
            } catch (DuplicateKeyException exception) {
                yaExistian++;
            }

            Query eventoLegacy = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(evento.getId()),
                    Criteria.where(CAMPO_AVISO_LEGACY).is(true)));
            mongoTemplate.updateFirst(eventoLegacy, new Update().unset(CAMPO_AVISO_LEGACY), Evento.class);
        }

        return new Resultado(migrados, yaExistian, omitidos);
    }

    private static String inconsistencia(Evento evento) {
        if (evento.getEstado() != EstadoEvento.CANCELADO) {
            return "el evento no está cancelado";
        }
        if (evento.getMotivoCancelacion() == null || evento.getMotivoCancelacion().isBlank()) {
            return "falta el motivo de cancelación";
        }
        if (evento.getCanceladoEn() == null) {
            return "falta la fecha de cancelación";
        }
        return null;
    }

    public record Resultado(int migrados, int yaExistian, int omitidos) {
    }
}
