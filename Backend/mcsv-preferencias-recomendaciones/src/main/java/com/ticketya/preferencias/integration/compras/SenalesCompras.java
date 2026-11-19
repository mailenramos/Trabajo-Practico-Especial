package com.ticketya.preferencias.integration.compras;

import java.util.Set;

public record SenalesCompras(Set<String> tiposComprados, Set<String> eventosCoComprados) {
    public SenalesCompras {
        tiposComprados = Set.copyOf(tiposComprados);
        eventosCoComprados = Set.copyOf(eventosCoComprados);
    }

    public static SenalesCompras vacias() {
        return new SenalesCompras(Set.of(), Set.of());
    }
}
