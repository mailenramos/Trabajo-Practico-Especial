package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.ticketya.gestionlugares.domain.AvisoOutbox;
import com.ticketya.gestionlugares.domain.EstadoAvisoOutbox;

@Repository
public class OutboxAvisoRepositoryCustomImpl implements OutboxAvisoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public OutboxAvisoRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<AvisoOutbox> reclamarSiguiente(Instant ahora, Instant leaseHasta, int maxIntentos) {
        Objects.requireNonNull(ahora, "ahora no puede ser null");
        Objects.requireNonNull(leaseHasta, "leaseHasta no puede ser null");
        if (!leaseHasta.isAfter(ahora)) {
            throw new IllegalArgumentException("leaseHasta debe ser posterior a ahora");
        }
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("maxIntentos debe ser mayor que cero");
        }

        Criteria pendienteVencido = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PENDIENTE),
                Criteria.where("proximoIntento").lte(ahora),
                Criteria.where("intentos").lt(maxIntentos));
        Criteria procesamientoVencido = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PROCESANDO),
                Criteria.where("leaseHasta").lte(ahora),
                Criteria.where("intentos").lt(maxIntentos));
        Query query = new Query(new Criteria().orOperator(pendienteVencido, procesamientoVencido));
        Update update = new Update()
                .set("estado", EstadoAvisoOutbox.PROCESANDO)
                .set("leaseHasta", leaseHasta)
                .set("actualizadoEn", ahora)
                .inc("intentos", 1);

        AvisoOutbox aviso = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), AvisoOutbox.class);
        return Optional.ofNullable(aviso);
    }
}
