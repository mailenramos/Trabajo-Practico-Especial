package com.ticketya.gestionlugares.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ticketya.gestionlugares.api.EventoBusquedaItem;
import com.ticketya.gestionlugares.api.EventoBusquedaResponse;
import com.ticketya.gestionlugares.api.EventoDetalleResponse;
import com.ticketya.gestionlugares.domain.EstadoEvento;
import com.ticketya.gestionlugares.domain.Evento;
import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.repository.EventoRepository;
import com.ticketya.gestionlugares.repository.LugarRepository;
import com.ticketya.gestionlugares.reservas.ReservasClient;
import com.ticketya.gestionlugares.reservas.ReservasClientException;
import com.ticketya.gestionlugares.reservas.dto.DisponibilidadEventoResponse;
import com.ticketya.gestionlugares.reservas.dto.SectorDisponibilidadResponse;

@Service
public class EventoConsultaService {
    private static final Logger log = LoggerFactory.getLogger(EventoConsultaService.class);
    private static final List<EstadoEvento> VISIBLES_EN_BUSQUEDA = List.of(EstadoEvento.PUBLICADO, EstadoEvento.AGOTADO);

    private final MongoTemplate mongoTemplate;
    private final EventoRepository eventoRepository;
    private final LugarRepository lugarRepository;
    private final ReservasClient reservasClient;

    public EventoConsultaService(MongoTemplate mongoTemplate, EventoRepository eventoRepository,
            LugarRepository lugarRepository, ReservasClient reservasClient) {
        this.mongoTemplate = mongoTemplate;
        this.eventoRepository = eventoRepository;
        this.lugarRepository = lugarRepository;
        this.reservasClient = reservasClient;
    }

    public EventoBusquedaResponse buscar(String tipo, String organizadorId, String participante,
            String ciudad, String fechaDesde, String fechaHasta, String orden, String direccion,
            String q, String pageParam, String sizeParam, String userId, String userRole) {
        validarRol(userId, userRole);
        int page = parsearEntero(pageParam, 0, "page");
        int size = parsearEntero(sizeParam, 20, "size");
        if (page < 0) throw new IllegalArgumentException("page debe ser mayor o igual a 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size debe estar entre 1 y 100");

        String ordenEfectivo = orden == null || orden.isBlank() ? "fecha" : orden.toLowerCase(Locale.ROOT);
        if (!List.of("fecha", "precio", "relevancia").contains(ordenEfectivo)) {
            throw new IllegalArgumentException("orden debe ser fecha, precio o relevancia");
        }
        String direccionEfectiva = direccion == null || direccion.isBlank() ? "asc" : direccion.toLowerCase(Locale.ROOT);
        if (!List.of("asc", "desc").contains(direccionEfectiva)) {
            throw new IllegalArgumentException("direccion debe ser asc o desc");
        }

        Instant desde = parsearFecha(fechaDesde, "fechaDesde");
        Instant hasta = parsearFecha(fechaHasta, "fechaHasta");
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new IllegalArgumentException("fechaDesde no puede ser posterior a fechaHasta");
        }

        List<Criteria> criterios = new ArrayList<>();
        criterios.add(Criteria.where("estado").in(VISIBLES_EN_BUSQUEDA));
        if (noVacio(tipo)) criterios.add(Criteria.where("tipo").is(tipo));
        if (noVacio(organizadorId)) criterios.add(Criteria.where("organizadorId").is(organizadorId));
        if (noVacio(participante)) criterios.add(Criteria.where("participantes").is(participante));
        if (noVacio(ciudad)) criterios.add(Criteria.where("ciudad").is(ciudad));
        if (desde != null || hasta != null) {
            Criteria fecha = Criteria.where("fechaHora");
            if (desde != null) fecha.gte(desde);
            if (hasta != null) fecha.lte(hasta);
            criterios.add(fecha);
        }
        if ("precio".equals(ordenEfectivo)) criterios.add(Criteria.where("precioMinimo").ne(null));

        Criteria filtro = new Criteria().andOperator(criterios.toArray(Criteria[]::new));
        Query query;
        Pageable pageable;
        if ("relevancia".equals(ordenEfectivo) && noVacio(q)) {
            TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matching(q.trim());
            query = TextQuery.queryText(textCriteria).sortByScore();
            query.addCriteria(filtro);
            query.with(Sort.by(Sort.Order.asc("fechaHora"), Sort.Order.asc("_id")));
            pageable = PageRequest.of(page, size);
        } else {
            query = new Query(filtro);
            Sort sort = switch (ordenEfectivo) {
                case "precio" -> Sort.by(new Sort.Order(
                        "desc".equals(direccionEfectiva) ? Sort.Direction.DESC : Sort.Direction.ASC,
                        "precioMinimo"), Sort.Order.asc("fechaHora"), Sort.Order.asc("_id"));
                default -> Sort.by(Sort.Order.asc("fechaHora"), Sort.Order.asc("_id"));
            };
            pageable = PageRequest.of(page, size, sort);
        }

        Query countQuery = Query.query(filtro);
        if ("relevancia".equals(ordenEfectivo) && noVacio(q)) {
            countQuery.addCriteria(TextCriteria.forDefaultLanguage().matching(q.trim()));
        }
        long total = mongoTemplate.count(countQuery, Evento.class);
        query.with(pageable);
        List<EventoBusquedaItem> contenido = mongoTemplate.find(query, Evento.class).stream()
                .map(EventoBusquedaItem::from).toList();
        int totalPaginas = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new EventoBusquedaResponse(contenido, page, size, total, totalPaginas);
    }

    public EventoDetalleResponse detalle(String eventoId, String userId, String userRole) {
        validarRol(userId, userRole);
        if (!ObjectId.isValid(eventoId)) throw new ResourceNotFoundException("No existe el evento " + eventoId);
        Evento evento = eventoRepository.findById(eventoId)
                .orElseThrow(() -> new ResourceNotFoundException("No existe el evento " + eventoId));
        boolean esAdmin = "ADMIN".equalsIgnoreCase(userRole.trim());
        if (evento.getEstado() == EstadoEvento.BORRADOR
                && !esAdmin && !evento.getOrganizadorId().equals(userId)) {
            throw new ResourceNotFoundException("No existe el evento " + eventoId);
        }
        Lugar lugar = lugarRepository.findById(evento.getLugarId())
                .orElseThrow(() -> new ResourceNotFoundException("No existe el lugar del evento"));
        return EventoDetalleResponse.from(evento, lugar, consultarDisponibilidad(evento));
    }

    private Map<String, SectorDisponibilidadResponse> consultarDisponibilidad(Evento evento) {
        if (evento.getEstado() != EstadoEvento.PUBLICADO && evento.getEstado() != EstadoEvento.AGOTADO) {
            return Map.of();
        }
        try {
            DisponibilidadEventoResponse respuesta = reservasClient.consultarDisponibilidad(evento.getId());
            if (respuesta == null || respuesta.sectores() == null) return Map.of();
            return respuesta.sectores().stream()
                    .filter(sector -> sector.sectorId() != null)
                    .collect(Collectors.toMap(SectorDisponibilidadResponse::sectorId, sector -> sector,
                            (primero, duplicado) -> primero, HashMap::new));
        } catch (ReservasClientException exception) {
            log.warn("No se pudo consultar disponibilidad en Reservas para el evento {}", evento.getId(), exception);
            return Map.of();
        }
    }

    private static void validarRol(String userId, String userRole) {
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
        }
        String rol = userRole.trim();
        if (!"CLIENTE".equalsIgnoreCase(rol) && !"ORGANIZADOR".equalsIgnoreCase(rol)
                && !"ADMIN".equalsIgnoreCase(rol)) {
            throw new ForbiddenException("X-User-Role debe ser CLIENTE, ORGANIZADOR o ADMIN");
        }
    }

    private static int parsearEntero(String value, int defecto, String nombre) {
        if (value == null || value.isBlank()) return defecto;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(nombre + " debe ser un entero");
        }
    }

    private static Instant parsearFecha(String value, String nombre) {
        if (value == null || value.isBlank()) return null;
        if (!value.endsWith("Z")) throw new IllegalArgumentException(nombre + " debe estar en ISO-8601 UTC (terminar en Z)");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(nombre + " debe estar en ISO-8601 UTC");
        }
    }

    private static boolean noVacio(String value) { return value != null && !value.isBlank(); }
}
