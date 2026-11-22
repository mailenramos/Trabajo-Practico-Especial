package com.ticketya.gestionlugares.reservas;

import java.util.function.Predicate;

public class Respuesta4xxReservasPredicate implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable error) {
        return error instanceof ReservasClientException exception && exception.esRespuesta4xx();
    }
}
