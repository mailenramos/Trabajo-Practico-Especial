package com.ticketya.gestionlugares.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import com.ticketya.gestionlugares.api.EventoBusquedaItem;
import com.ticketya.gestionlugares.api.EventoBusquedaResponse;
import com.ticketya.gestionlugares.config.CatalogoCacheConfiguration;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;

@Service
public class CatalogoBusquedaCache {

    private final MongoTemplate mongoTemplate;

    public CatalogoBusquedaCache(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Cacheable(cacheNames = CatalogoCacheConfiguration.BUSQUEDA_EVENTOS)
    public EventoBusquedaResponse buscar(BusquedaCatalogo busqueda) {
        List<Criteria> criterios = new ArrayList<>();
        criterios.add(Criteria.where("estado").in(EstadoEvento.PUBLICADO, EstadoEvento.AGOTADO));
        if (busqueda.tipo() != null) criterios.add(Criteria.where("tipo").is(busqueda.tipo()));
        if (busqueda.organizadorId() != null) criterios.add(Criteria.where("organizadorId").is(busqueda.organizadorId()));
        if (busqueda.participante() != null) criterios.add(Criteria.where("participantes").is(busqueda.participante()));
        if (busqueda.ciudad() != null) criterios.add(Criteria.where("ciudad").is(busqueda.ciudad()));
        if (busqueda.desde() != null || busqueda.hasta() != null) {
            Criteria fecha = Criteria.where("fechaHora");
            if (busqueda.desde() != null) fecha.gte(busqueda.desde());
            if (busqueda.hasta() != null) fecha.lte(busqueda.hasta());
            criterios.add(fecha);
        }
        if ("precio".equals(busqueda.orden())) criterios.add(Criteria.where("precioMinimo").ne(null));

        Criteria filtro = new Criteria().andOperator(criterios.toArray(Criteria[]::new));
        Query query;
        Pageable pageable;
        if ("relevancia".equals(busqueda.orden()) && busqueda.q() != null) {
            TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matching(busqueda.q());
            query = TextQuery.queryText(textCriteria).sortByScore();
            query.addCriteria(filtro);
            query.with(Sort.by(Sort.Order.asc("fechaHora"), Sort.Order.asc("_id")));
            pageable = PageRequest.of(busqueda.page(), busqueda.size());
        } else {
            query = new Query(filtro);
            Sort sort = switch (busqueda.orden()) {
                case "precio" -> Sort.by(new Sort.Order(
                        "desc".equals(busqueda.direccion()) ? Sort.Direction.DESC : Sort.Direction.ASC,
                        "precioMinimo"), Sort.Order.asc("fechaHora"), Sort.Order.asc("_id"));
                default -> Sort.by(Sort.Order.asc("fechaHora"), Sort.Order.asc("_id"));
            };
            pageable = PageRequest.of(busqueda.page(), busqueda.size(), sort);
        }

        Query countQuery = Query.query(filtro);
        if ("relevancia".equals(busqueda.orden()) && busqueda.q() != null) {
            countQuery.addCriteria(TextCriteria.forDefaultLanguage().matching(busqueda.q()));
        }
        long total = mongoTemplate.count(countQuery, Evento.class);
        query.with(pageable);
        List<EventoBusquedaItem> contenido = mongoTemplate.find(query, Evento.class).stream()
                .map(EventoBusquedaItem::from).toList();
        int totalPaginas = total == 0 ? 0 : (int) ((total + busqueda.size() - 1) / busqueda.size());
        return new EventoBusquedaResponse(contenido, busqueda.page(), busqueda.size(), total, totalPaginas);
    }
}
