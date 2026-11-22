package com.ticketya.gestionlugares.usuarios;

import java.util.function.Predicate;

import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.usuarios.http.HttpUsuariosClient;

public class ErroresIgnoradosUsuariosPredicate implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable error) {
        return error instanceof ResourceNotFoundException
                || error instanceof HttpUsuariosClient.Respuesta4xxException
                || error instanceof HttpUsuariosClient.ConfiguracionException;
    }
}
