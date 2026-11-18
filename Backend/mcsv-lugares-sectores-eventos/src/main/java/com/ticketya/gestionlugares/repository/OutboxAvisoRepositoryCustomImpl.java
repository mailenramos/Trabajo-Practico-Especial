package com.ticketya.gestionlugares.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.Sort;
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
        query.with(Sort.by(Sort.Direction.ASC, "proximoIntento"));
        Update update = new Update()
                .set("estado", EstadoAvisoOutbox.PROCESANDO)
                .set("leaseHasta", leaseHasta)
                .set("actualizadoEn", ahora)
                .inc("intentos", 1);

        AvisoOutbox aviso = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), AvisoOutbox.class);
        return Optional.ofNullable(aviso);
    }

    @Override
    public long marcarAvisosAgotados(Instant ahora, int maxIntentos) {
        Objects.requireNonNull(ahora, "ahora no puede ser null");
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("maxIntentos debe ser mayor que cero");
        }

        Criteria pendienteAgotado = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PENDIENTE),
                Criteria.where("intentos").gte(maxIntentos));
        Criteria procesamientoAgotado = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PROCESANDO),
                Criteria.where("leaseHasta").lte(ahora),
                Criteria.where("intentos").gte(maxIntentos));
        Query query = new Query(new Criteria().orOperator(pendienteAgotado, procesamientoAgotado));
        Update update = new Update()
                .set("estado", EstadoAvisoOutbox.ERROR_FINAL)
                .set("ultimoError", "intentos agotados")
                .set("actualizadoEn", ahora)
                .unset("leaseHasta");

        return mongoTemplate.updateMulti(query, update, AvisoOutbox.class).getModifiedCount();
    }
}
