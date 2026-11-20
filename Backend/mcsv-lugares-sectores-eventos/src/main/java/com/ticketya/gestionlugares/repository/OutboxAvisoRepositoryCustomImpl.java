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
        validarReclamo(ahora, leaseHasta, maxIntentos);
        return reclamar(new Query(avisosElegibles(ahora, maxIntentos)), ahora, leaseHasta);
    }

    @Override
    public Optional<AvisoOutbox> reclamarPorId(String avisoId, Instant ahora, Instant leaseHasta, int maxIntentos) {
        Objects.requireNonNull(avisoId, "avisoId no puede ser null");
        validarReclamo(ahora, leaseHasta, maxIntentos);
        Criteria porId = Criteria.where("id").is(avisoId);
        Criteria elegible = new Criteria().andOperator(porId, avisosElegibles(ahora, maxIntentos));
        return reclamar(new Query(elegible), ahora, leaseHasta);
    }

    @Override
    public boolean confirmarReclamo(AvisoOutbox aviso, Instant ahora) {
        Objects.requireNonNull(aviso.getLeaseHasta(), "El reclamo debe tener lease");
        Query query = reclamoVigente(aviso, aviso.getLeaseHasta(), ahora);
        Update update = new Update().set("estado", EstadoAvisoOutbox.ENTREGADO)
                .set("entregadoEn", ahora).set("actualizadoEn", ahora)
                .unset("ultimoError").unset("leaseHasta");
        return mongoTemplate.updateFirst(query, update, AvisoOutbox.class).getModifiedCount() == 1;
    }

    @Override
    public boolean guardarResultadoReclamo(AvisoOutbox aviso, Instant leaseHasta, Instant ahora) {
        Update update = new Update().set("estado", aviso.getEstado())
                .set("ultimoError", aviso.getUltimoError()).set("actualizadoEn", ahora)
                .set("proximoIntento", aviso.getProximoIntento()).unset("leaseHasta");
        return mongoTemplate.updateFirst(reclamoVigente(aviso, leaseHasta, ahora),
                update, AvisoOutbox.class).getModifiedCount() == 1;
    }

    private Query reclamoVigente(AvisoOutbox aviso, Instant leaseHasta, Instant ahora) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(aviso.getId()),
                Criteria.where("estado").is(EstadoAvisoOutbox.PROCESANDO),
                Criteria.where("intentos").is(aviso.getIntentos()),
                Criteria.where("leaseHasta").is(leaseHasta),
                Criteria.where("leaseHasta").gt(ahora)));
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

    private Optional<AvisoOutbox> reclamar(Query query, Instant ahora, Instant leaseHasta) {
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

    private static Criteria avisosElegibles(Instant ahora, int maxIntentos) {
        Criteria pendienteVencido = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PENDIENTE),
                Criteria.where("proximoIntento").lte(ahora),
                Criteria.where("intentos").lt(maxIntentos));
        Criteria procesamientoVencido = new Criteria().andOperator(
                Criteria.where("estado").is(EstadoAvisoOutbox.PROCESANDO),
                Criteria.where("leaseHasta").lte(ahora),
                Criteria.where("intentos").lt(maxIntentos));
        return new Criteria().orOperator(pendienteVencido, procesamientoVencido);
    }

    private static void validarReclamo(Instant ahora, Instant leaseHasta, int maxIntentos) {
        Objects.requireNonNull(ahora, "ahora no puede ser null");
        Objects.requireNonNull(leaseHasta, "leaseHasta no puede ser null");
        if (!leaseHasta.isAfter(ahora)) {
            throw new IllegalArgumentException("leaseHasta debe ser posterior a ahora");
        }
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("maxIntentos debe ser mayor que cero");
        }
    }
}
