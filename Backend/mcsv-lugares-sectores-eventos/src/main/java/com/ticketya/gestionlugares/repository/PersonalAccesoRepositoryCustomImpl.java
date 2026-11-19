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

import com.ticketya.gestionlugares.domain.AsignacionPersonalAcceso;
import com.ticketya.gestionlugares.domain.EstadoPersonalAcceso;

@Repository
public class PersonalAccesoRepositoryCustomImpl implements PersonalAccesoRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public PersonalAccesoRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<AsignacionPersonalAcceso> revocarSiActiva(String eventoId, String usuarioId, Instant revocadaEn) {
        Objects.requireNonNull(revocadaEn, "revocadaEn no puede ser null");
        Update update = new Update().set("estado", EstadoPersonalAcceso.REVOCADA)
                .set("revocadaEn", revocadaEn);
        return transicionar(eventoId, usuarioId, EstadoPersonalAcceso.ACTIVA, update);
    }

    @Override
    public Optional<AsignacionPersonalAcceso> reactivarSiRevocada(String eventoId, String usuarioId, Instant asignadaEn) {
        Objects.requireNonNull(asignadaEn, "asignadaEn no puede ser null");
        Update update = new Update().set("estado", EstadoPersonalAcceso.ACTIVA)
                .set("asignadaEn", asignadaEn).set("revocadaEn", null);
        return transicionar(eventoId, usuarioId, EstadoPersonalAcceso.REVOCADA, update);
    }

    private Optional<AsignacionPersonalAcceso> transicionar(String eventoId, String usuarioId,
            EstadoPersonalAcceso estado, Update update) {
        Objects.requireNonNull(eventoId, "eventoId no puede ser null");
        Objects.requireNonNull(usuarioId, "usuarioId no puede ser null");
        Query query = Query.query(Criteria.where("eventoId").is(eventoId)
                .and("usuarioId").is(usuarioId).and("estado").is(estado));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), AsignacionPersonalAcceso.class));
    }
}
